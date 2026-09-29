// Package demostats 用 demoinfocs-golang 对 CS2 demo 做本地解析，
// 产出 Steam 官匹战绩页**拿不到**的字段：伤害(ADR)、KAST、多杀、开局杀、真实存活、回合经济。
//
// 输出 JSON 是手机端与本工具共用的契约（version 字段控制兼容性）：
//
//	{
//	  "version": 1,
//	  "parser": "demoinfocs-golang v5.2.0",
//	  "mapName": "de_ancient",
//	  "durationSec": 2940,
//	  "hasEconomy": true,
//	  "warmupSkipped": true,
//	  "players": [ { "steamId": "...", "name": "...", "team": "CT",
//	                 "rounds": 24, "kills": 21, "deaths": 18, "assists": 6,
//	                 "hsKills": 9, "damage": 1720, "kastRounds": 16,
//	                 "survivedRounds": 6, "multiKillRounds": 3,
//	                 "openingKills": 2, "openingDeaths": 3, "mvps": 4 } ],
//	  "rounds": [ { "num": 1, "winner": "CT", "lengthSec": 92, "ctBuy": 24500, "tBuy": 19000 } ]
//	}
//
// 口径说明（与 HLTV 一致、不编造）：
//
//   - KAST = 该回合内「有击杀 或 有助攻 或 有补枪(被杀者 5 秒内杀过我方队友) 或 存活」；
//   - 开局杀 = 本回合**首个击杀**的凶手；开局死 = 本回合**首个阵亡**者（含摔死/世界伤害）；
//   - 多杀 = 单回合 ≥2 杀；
//   - 存活 = 该回合上场且回合内没死过；
//   - 伤害 = PlayerHurt 的 HealthDamage 累计（含过量伤害，与记分板口径一致）；
//   - 热身（warmup）回合整体跳过，不计入任何统计；机器人（IsBot / 无 SteamID）不进名单。
//
// 字段拿不到时保持 0，**绝不猜测**：调用方自行判断可信度。
package demostats

import (
	"compress/bzip2"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs"
	common "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/common"
	events "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/events"
	msg "github.com/markus-wa/demoinfocs-golang/v5/pkg/demoinfocs/msg"
)

// Version 输出 JSON 的契约版本。
const Version = 1

// ParserID 解析器标识，随结果一起落盘，方便日后核对口径。
const ParserID = "demoinfocs-golang v5.2.0"

// Player 单人全场统计。字段名与手机端 ApiClient 的 JSON 契约保持一致。
type Player struct {
	SteamID         string `json:"steamId"`
	Name            string `json:"name"`
	Team            string `json:"team"` // 结束时所在边：CT / T
	Rounds          int    `json:"rounds"`
	Kills           int    `json:"kills"`
	Deaths          int    `json:"deaths"`
	Assists         int    `json:"assists"`
	HeadshotKills   int    `json:"hsKills"`
	Damage          int    `json:"damage"`
	KastRounds      int    `json:"kastRounds"`
	SurvivedRounds  int    `json:"survivedRounds"`
	MultiKillRounds int    `json:"multiKillRounds"`
	OpeningKills    int    `json:"openingKills"`
	OpeningDeaths   int    `json:"openingDeaths"`
	MVPs            int    `json:"mvps"`
	IsBot           bool   `json:"isBot"`
}

// Round 单回合摘要（供核对与后续 Round Swing 精确化；当前版本不回写档案）。
type Round struct {
	Num       int    `json:"num"`
	Winner    string `json:"winner"` // CT / T / none
	LengthSec int    `json:"lengthSec"`
	CTBuy     int    `json:"ctBuy"` // 冻结期结束装备总值（0 = 拿不到）
	TBuy      int    `json:"tBuy"`
}

// Result 解析总结果。
type Result struct {
	Version       int       `json:"version"`
	Parser        string    `json:"parser"`
	MapName       string    `json:"mapName"`
	DurationSec   int       `json:"durationSec"`
	WarmupSkipped bool      `json:"warmupSkipped"`
	HasEconomy    bool      `json:"hasEconomy"`
	Players       []*Player `json:"players"`
	Rounds        []Round   `json:"rounds"`
	Note          string    `json:"note,omitempty"`
}

// JSON 序列化。
func (r *Result) JSON(indent bool) string {
	var b []byte
	var err error
	if indent {
		b, err = json.MarshalIndent(r, "", "  ")
	} else {
		b, err = json.Marshal(r)
	}
	if err != nil {
		return fmt.Sprintf(`{"version":%d,"error":%q}`, Version, err.Error())
	}
	return string(b)
}

// ---------- 进度回调 ----------

// ErrCanceled 主动取消（CancelParse / 调用方 stop 回调）。
var ErrCanceled = errors.New("解析已取消")

// countingReader 统计底层文件已读字节（垫在 bzip2 解压器下面，进度按压缩包体积算）。
type countingReader struct {
	src   io.Reader
	read  int64
	total int64
	cb    func(float64)
	stop  func() bool // 返回 true 立即中断
	last  time.Time
	lastP float64
}

func (c *countingReader) Read(p []byte) (int, error) {
	if c.stop != nil && c.stop() {
		return 0, ErrCanceled
	}
	n, err := c.src.Read(p)
	if n > 0 {
		c.read += int64(n)
		if c.total > 0 && c.cb != nil {
			now := time.Now()
			v := float64(c.read) / float64(c.total)
			if v > 1 {
				v = 1
			}
			if now.Sub(c.last) > 150*time.Millisecond || v-c.lastP > 0.01 {
				c.last = now
				c.lastP = v
				c.cb(v)
			}
		}
	}
	return n, err
}

// ---------- 逐回合跟踪 ----------

type killInfo struct {
	frame      int
	victimSide string
}

type roundCtx struct {
	active       bool
	num          int
	startTime    time.Duration
	participants map[uint64]bool // steamId64 -> 本回合上场（真人）
	died         map[uint64]bool
	kills        map[uint64]int
	assists      map[uint64]int
	trades       map[uint64]bool
	firstKillBy  uint64
	firstDeathBy uint64
	ctBuy        int
	tBuy         int
}

type tracker struct {
	p       demoinfocs.Parser
	players map[uint64]*Player
	order   []*Player

	cur        *roundCtx
	roundNum   int
	roundList  []Round
	warmupSeen bool
	mapName    string

	lastKill map[*common.Player]killInfo // 补枪判定：该玩家最近一次击杀
}

func sideOf(t common.Team) string {
	switch t {
	case common.TeamCounterTerrorists:
		return "CT"
	case common.TeamTerrorists:
		return "T"
	default:
		return "none"
	}
}

// playerFor 取（或登记）真人玩家条目；机器人/无 SteamID 返回 nil。
// 顺带刷新所在边（半场换边后 Player.Team 是实时的）。
func (t *tracker) playerFor(pl *common.Player) *Player {
	if pl == nil || pl.IsBot || pl.SteamID64 == 0 {
		return nil
	}
	p, ok := t.players[pl.SteamID64]
	if !ok {
		p = &Player{
			SteamID: strconv.FormatUint(pl.SteamID64, 10),
			Name:    pl.Name,
		}
		t.players[pl.SteamID64] = p
		t.order = append(t.order, p)
	}
	if s := sideOf(pl.Team); s != "none" {
		p.Team = s
	}
	return p
}

func (t *tracker) warmup() bool {
	return t.p.GameState().IsWarmupPeriod()
}

// ensureRound 保证当前有一个进行中的回合（RoundStart / RoundFreezetimeEnd 时调用）。
func (t *tracker) ensureRound() bool {
	if t.warmup() {
		t.warmupSeen = true
		if t.cur != nil {
			t.cur.active = false
		}
		return false
	}
	if t.cur == nil || !t.cur.active {
		t.roundNum++
		t.cur = &roundCtx{
			active:       true,
			num:          t.roundNum,
			startTime:    t.p.CurrentTime(),
			participants: make(map[uint64]bool),
			died:         make(map[uint64]bool),
			kills:        make(map[uint64]int),
			assists:      make(map[uint64]int),
			trades:       make(map[uint64]bool),
		}
		for _, pl := range t.p.GameState().Participants().Playing() {
			if pl == nil || pl.IsBot || pl.SteamID64 == 0 {
				continue
			}
			t.playerFor(pl)
			t.cur.participants[pl.SteamID64] = true
		}
	}
	return t.cur.active
}

// endRound 收尾：把本回合的击杀/助攻/补枪/存活/多杀/开局杀落到各人总账。
func (t *tracker) endRound(winner common.Team) {
	cur := t.cur
	if cur == nil || !cur.active {
		return
	}

	kast := make(map[uint64]bool, len(cur.participants))
	for sid := range cur.participants {
		p, ok := t.players[sid]
		if !ok {
			continue
		}
		p.Rounds++
		if cur.kills[sid] > 0 || cur.assists[sid] > 0 || cur.trades[sid] {
			kast[sid] = true
		}
		if !cur.died[sid] {
			p.SurvivedRounds++
		}
		if cur.kills[sid] >= 2 {
			p.MultiKillRounds++
		}
		if cur.firstKillBy == sid {
			p.OpeningKills++
		}
		if cur.firstDeathBy == sid {
			p.OpeningDeaths++
		}
	}
	for sid, ok := range kast {
		if ok {
			if p, exists := t.players[sid]; exists {
				p.KastRounds++
			}
		}
	}

	length := int((t.p.CurrentTime() - cur.startTime).Seconds())
	if length < 0 {
		length = 0
	}
	t.roundList = append(t.roundList, Round{
		Num:       cur.num,
		Winner:    sideOf(winner),
		LengthSec: length,
		CTBuy:     cur.ctBuy,
		TBuy:      cur.tBuy,
	})

	cur.active = false
}

// ---------- 主流程 ----------

// Parse 解析 demo 文件。path 支持 .dem 与 .dem.bz2（Valve 官方回放就是 bz2）。
// cb 为进度回调（0..1，按压缩包已读字节算），可为 nil。
func Parse(path string, cb func(float64)) (*Result, error) {
	return ParseCancel(path, cb, nil)
}

// ParseCancel 同 Parse，但 stop 返回 true 时会尽快中断并返回 [ErrCanceled]。
//
// **任何来自解析库的 panic（文件损坏/截断时 demoinfocs 会 nil 解引用）都会被转成错误**——
// 手机上这是跑在 JNI 线程里的，panic 会直接带走整个 App 进程。
func ParseCancel(path string, cb func(float64), stop func() bool) (res *Result, err error) {
	defer func() {
		if r := recover(); r != nil {
			res, err = nil, fmt.Errorf("解析异常（文件可能已损坏或未下载完整）: %v", r)
		}
	}()
	if stop != nil && stop() {
		return nil, ErrCanceled
	}

	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("打开 demo 失败: %w", err)
	}
	defer f.Close()

	var total int64
	if st, e := f.Stat(); e == nil {
		total = st.Size()
	}

	cr := &countingReader{src: f, total: total, cb: cb, stop: stop}
	var reader io.Reader = cr
	if strings.HasSuffix(strings.ToLower(path), ".bz2") {
		reader = bzip2.NewReader(cr)
	}

	p := demoinfocs.NewParser(reader)
	defer p.Close()

	t := &tracker{
		p:        p,
		players:  make(map[uint64]*Player),
		lastKill: make(map[*common.Player]killInfo),
	}

	// 地图名
	p.RegisterNetMessageHandler(func(m *msg.CSVCMsg_ServerInfo) {
		if n := m.GetMapName(); n != "" {
			t.mapName = n
		}
	})

	// 回合开始
	p.RegisterEventHandler(func(events.RoundStart) {
		t.ensureRound()
	})

	// 冻结期结束：记双方装备值（后续 Round Swing / eco 修正的原料），同时兜底开回合
	p.RegisterEventHandler(func(events.RoundFreezetimeEnd) {
		if !t.ensureRound() {
			return
		}
		for _, pl := range t.p.GameState().Participants().Playing() {
			if pl == nil || pl.IsBot {
				continue
			}
			v := pl.EquipmentValueFreezeTimeEnd()
			switch pl.Team {
			case common.TeamCounterTerrorists:
				t.cur.ctBuy += v
			case common.TeamTerrorists:
				t.cur.tBuy += v
			}
		}
	})

	// 击杀
	p.RegisterEventHandler(func(e events.Kill) {
		if t.cur == nil || !t.cur.active || t.warmup() {
			return
		}
		frame := t.p.CurrentFrame()
		killer, victim, assister := e.Killer, e.Victim, e.Assister

		// 受害者：阵亡 + 首死 + 补枪判定
		if victim != nil {
			if pl := t.playerFor(victim); pl != nil {
				pl.Deaths++
				t.cur.died[victim.SteamID64] = true
			}
			if t.cur.firstDeathBy == 0 {
				t.cur.firstDeathBy = victim.SteamID64
			}
		}

		// 补枪：被杀者在 5 秒内杀过凶手同队的人
		if killer != nil && victim != nil && killer != victim {
			tradeWindow := int(5.0 * p.TickRate())
			if info, ok := t.lastKill[victim]; ok {
				if frame-info.frame <= tradeWindow && info.victimSide == sideOf(killer.Team) {
					t.cur.trades[killer.SteamID64] = true
				}
			}
		}

		// 凶手：击杀 + 爆头 + 首杀
		if killer != nil {
			if pl := t.playerFor(killer); pl != nil {
				pl.Kills++
				if e.IsHeadshot {
					pl.HeadshotKills++
				}
				t.cur.kills[killer.SteamID64]++
			}
			if t.cur.firstKillBy == 0 {
				t.cur.firstKillBy = killer.SteamID64
			}
			if victim != nil {
				t.lastKill[killer] = killInfo{frame: frame, victimSide: sideOf(victim.Team)}
			}
		}

		// 助攻
		if assister != nil {
			if pl := t.playerFor(assister); pl != nil {
				pl.Assists++
				t.cur.assists[assister.SteamID64]++
			}
		}
	})

	// 伤害
	p.RegisterEventHandler(func(e events.PlayerHurt) {
		if t.cur == nil || !t.cur.active || t.warmup() {
			return
		}
		if e.Attacker == nil || e.Player == nil || e.Attacker == e.Player {
			return // 世界伤害 / 自伤
		}
		if pl := t.playerFor(e.Attacker); pl != nil {
			pl.Damage += e.HealthDamage
		}
	})

	// MVP ★（round_mvp 事件在 CS2 demo 里拿不到 userid，这里只当兜底；
	// 主口径是解析结束后读实体属性 m_iMVPs，见 Parse 尾部）
	p.RegisterEventHandler(func(e events.RoundMVPAnnouncement) {
		if t.warmup() {
			return
		}
		if pl := t.playerFor(e.Player); pl != nil {
			pl.MVPs++
		}
	})

	// 回合结束（只有真实回合会进到这里）
	p.RegisterEventHandler(func(e events.RoundEnd) {
		if t.warmup() {
			if t.cur != nil {
				t.cur.active = false
			}
			return
		}
		t.endRound(e.Winner)
	})

	if err := p.ParseToEnd(); err != nil {
		if stop != nil && stop() {
			return nil, ErrCanceled
		}
		return nil, fmt.Errorf("解析失败: %w", err)
	}
	if stop != nil && stop() {
		return nil, ErrCanceled
	}
	if cb != nil {
		cb(1.0)
	}

	// 从实体属性回填 ★MVP(m_iMVPs) 并刷新最终所在边。
	// 这与网页记分板读的是同一个实体值，实测 10/10 与战绩页 ★ 完全一致。
	//
	// 注：曾顺带读 m_iScore 填 Score，但实测 10 人里有 2 人与网页差 1~2 分、方向不一致
	// （无法解释的口径差），故**不输出 Score**——宁缺勿假，Score 一律以战绩页为准。
	for _, pl := range p.GameState().Participants().All() {
		if pl == nil || pl.Entity == nil || pl.IsBot || pl.SteamID64 == 0 {
			continue
		}
		st, ok := t.players[pl.SteamID64]
		if !ok {
			continue
		}
		if s := sideOf(pl.Team); s != "none" {
			st.Team = s
		}
		if v := pl.MVPs(); v > st.MVPs {
			st.MVPs = v
		}
	}

	res = &Result{
		Version:       Version,
		Parser:        ParserID,
		MapName:       t.mapName,
		DurationSec:   int(p.CurrentTime().Seconds()),
		WarmupSkipped: t.warmupSeen,
		Players:       t.order,
		Rounds:        t.roundList,
	}
	if res.MapName == "" {
		res.MapName = "未知"
	}
	for _, r := range t.roundList {
		if r.CTBuy > 0 || r.TBuy > 0 {
			res.HasEconomy = true
			break
		}
	}
	if len(res.Players) == 0 {
		res.Note = "demo 中没有可识别的真人玩家"
	} else if len(t.roundList) == 0 {
		res.Note = "demo 中没有统计到完整回合（可能只有热身）"
	}

	// 自检：结果必须能序列化
	if !json.Valid([]byte(res.JSON(false))) {
		return nil, fmt.Errorf("结果 JSON 非法")
	}
	return res, nil
}
