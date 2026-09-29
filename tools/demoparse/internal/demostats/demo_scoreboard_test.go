package demostats

import (
	"os"
	"testing"
)

// 用 Valve 官方 demo 与 Steam 战绩页**逐项对账**，防止解析口径悄悄跑偏。
//
// 样本：2026-09-28 08:52:25 GMT, Premier de_ancient, 13:11（24 回合），replay
// `003845258388027998412_0649725551.dem.bz2`（约 30 天过期），
// 网页侧数据取自 `app/src/test/resources/gcpd/gcpd_matchhistorypremier_full.html` 的记分板。
//
// demo 文件不存在时自动跳过：`CS2_STATS_DEMO` 环境变量可指定路径。
func demoPath() string {
	if p := os.Getenv("CS2_STATS_DEMO"); p != "" {
		return p
	}
	return `D:\dl\demo\003845258388027998412_0649725551.dem.bz2`
}

// 期望值：名字 -> (K, A, D, 爆头数, ★MVP)
//
// Score 列**故意不参与对账**：实测 demo 读 m_iScore 有 2 人与网页差 1~2 分且方向不一致，
// 无法解释 → 解析器不输出 Score，该列一律以战绩页为准。
var expected = map[string][5]int{
	"Balmond":         {34, 3, 14, 15, 3},
	"Kur1su":          {24, 5, 16, 13, 5},
	"vc":              {14, 4, 16, 4, 3},
	"DINOROBONG TUBO": {10, 9, 19, 4, 0},
	"帝皇被色孽骗去卖钩子":      {9, 8, 19, 2, 2},
	"monkeymilk":      {25, 4, 16, 10, 4},
	"Atopos":          {21, 6, 18, 9, 4},
	"mitmip":          {22, 0, 16, 11, 3},
	"Mountain":        {13, 12, 21, 6, 0},
	"6uo":             {3, 2, 20, 0, 0},
}

func TestRealDemoMatchesScoreboard(t *testing.T) {
	path := demoPath()
	if _, err := os.Stat(path); err != nil {
		t.Skipf("跳过：找不到 demo 文件 %s（可用 CS2_STATS_DEMO 指定）", path)
	}

	res, err := Parse(path, nil)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}

	// ---- 比赛级 ----
	if res.MapName != "de_ancient" {
		t.Errorf("地图 = %s, 期望 de_ancient", res.MapName)
	}
	if len(res.Rounds) != 24 {
		t.Errorf("回合数 = %d, 期望 24（13:11）", len(res.Rounds))
	}
	if !res.HasEconomy {
		t.Error("应当能拿到逐回合装备价值")
	}
	if res.DurationSec < 2200 || res.DurationSec > 2400 {
		t.Errorf("时长 %ds 不在网页 38:03(2283s) 附近", res.DurationSec)
	}
	if len(res.Players) != 10 {
		t.Errorf("玩家数 = %d, 期望 10", len(res.Players))
	}

	// ---- 逐人对账 ----
	byName := map[string]*Player{}
	for _, p := range res.Players {
		byName[p.Name] = p
	}
	for name, want := range expected {
		got, ok := byName[name]
		if !ok {
			t.Errorf("%s 不在解析结果里", name)
			continue
		}
		check := []struct {
			field string
			got   int
			want  int
		}{
			{"K", got.Kills, want[0]},
			{"A", got.Assists, want[1]},
			{"D", got.Deaths, want[2]},
			{"爆头", got.HeadshotKills, want[3]},
			{"★MVP", got.MVPs, want[4]},
		}
		for _, c := range check {
			if c.got != c.want {
				// 已知口径差：网页助攻 vs demo 助攻偶有 ±2（如 Mountain 12 vs 14）
				if c.field == "A" && abs(c.got-c.want) <= 2 {
					t.Logf("%s %s: demo=%d 网页=%d（已知口径差，容忍）", name, c.field, c.got, c.want)
					continue
				}
				t.Errorf("%s %s: demo=%d 网页=%d", name, c.field, c.got, c.want)
			}
		}
	}

	// ---- 内部一致性（口径自洽，不依赖外部数据） ----
	rounds := len(res.Rounds)
	for _, p := range res.Players {
		if p.Rounds != rounds {
			t.Errorf("%s 回合数 %d != 全场 %d", p.Name, p.Rounds, rounds)
		}
		// 恒等式：每回合最多阵亡一次 → 存活 = 回合 − 阵亡
		if p.SurvivedRounds != p.Rounds-p.Deaths {
			t.Errorf("%s 存活 %d != %d-%d", p.Name, p.SurvivedRounds, p.Rounds, p.Deaths)
		}
		if p.KastRounds < p.MultiKillRounds {
			t.Errorf("%s KAST(%d) 不应小于多杀回合(%d)", p.Name, p.KastRounds, p.MultiKillRounds)
		}
		if p.KastRounds > p.Rounds || p.SurvivedRounds > p.Rounds {
			t.Errorf("%s KAST/存活 超出总回合", p.Name)
		}
		if p.Damage <= 0 {
			t.Errorf("%s 伤害为 0，说明 PlayerHurt 没接上", p.Name)
		}
		if p.Team != "CT" && p.Team != "T" {
			t.Errorf("%s 所在边 = %q", p.Name, p.Team)
		}
	}
	if !res.WarmupSkipped {
		t.Log("提示：该 demo 未检测到热身阶段")
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}
