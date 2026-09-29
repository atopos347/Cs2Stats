package demostats

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestParseErrors 输入不存在/非法路径时必须返回错误而不是 panic。
func TestParseErrors(t *testing.T) {
	if _, err := Parse(filepath.Join(t.TempDir(), "nope.dem.bz2"), nil); err == nil {
		t.Fatal("不存在的文件应当返回错误")
	}

	bad := filepath.Join(t.TempDir(), "bad.dem.bz2")
	if err := os.WriteFile(bad, []byte("这不是 bz2"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Parse(bad, nil); err == nil {
		t.Fatal("损坏的 demo 应当返回错误")
	}
}

// TestCancel 取消必须尽早返回 ErrCanceled。
func TestCancel(t *testing.T) {
	if _, err := ParseCancel(filepath.Join(t.TempDir(), "nope.dem"), nil, func() bool { return true }); err != ErrCanceled {
		t.Fatalf("期望 ErrCanceled，实际 %v", err)
	}
}

// TestJSONShape 校验输出契约的字段名（手机端 ApiClient 按这些名字读）。
func TestJSONShape(t *testing.T) {
	r := &Result{
		Version: Version,
		Parser:  ParserID,
		MapName: "de_mirage",
		Players: []*Player{{
			SteamID: "76561198000000000", Name: "Atopos", Team: "T",
			Rounds: 24, Kills: 21, Deaths: 18, Assists: 6, HeadshotKills: 9,
			Damage: 2477, KastRounds: 16, SurvivedRounds: 6, MultiKillRounds: 8,
			OpeningKills: 3, OpeningDeaths: 4, MVPs: 4,
		}},
		Rounds: []Round{{Num: 1, Winner: "CT", LengthSec: 86, CTBuy: 24500, TBuy: 1750}},
	}
	js := r.JSON(false)
	for _, key := range []string{
		`"version"`, `"parser"`, `"mapName"`, `"durationSec"`, `"hasEconomy"`,
		`"steamId"`, `"rounds"`, `"kills"`, `"deaths"`, `"assists"`, `"hsKills"`,
		`"damage"`, `"kastRounds"`, `"survivedRounds"`, `"multiKillRounds"`,
		`"openingKills"`, `"openingDeaths"`, `"mvps"`, `"winner"`,
	} {
		if !strings.Contains(js, key) {
			t.Errorf("输出缺少字段 %s", key)
		}
	}
}
