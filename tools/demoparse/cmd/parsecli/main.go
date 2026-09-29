// parsecli —— 桌面端验证工具。
//
// 用途：在把同一份解析代码交叉编译成安卓 lib 之前，先在 PC 上用真 demo 核对数字
// （与 Steam 战绩页的 K/A/D/HS 逐项比对），确认口径无误。
//
//	go run ./cmd/parsecli -demo xxx.dem.bz2 [-out result.json] [-indent] [-quiet]
package main

import (
	"flag"
	"fmt"
	"os"
	"time"

	"cs2stats/demoparse/internal/demostats"
)

func main() {
	demo := flag.String("demo", "", "demo 文件路径（.dem 或 .dem.bz2）")
	out := flag.String("out", "", "把 JSON 写入该文件（默认打印到 stdout）")
	indent := flag.Bool("indent", false, "JSON 缩进输出")
	quiet := flag.Bool("quiet", false, "不打印进度")
	flag.Parse()

	if *demo == "" {
		fmt.Fprintln(os.Stderr, "用法: parsecli -demo <path> [-out <file>] [-indent] [-quiet]")
		os.Exit(2)
	}

	var lastPrint time.Time
	res, err := demostats.Parse(*demo, func(p float64) {
		if *quiet {
			return
		}
		if time.Since(lastPrint) > time.Second {
			lastPrint = time.Now()
			fmt.Fprintf(os.Stderr, "\r进度 %5.1f%%", p*100)
		}
	})
	if !*quiet {
		fmt.Fprintln(os.Stderr)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "错误:", err)
		os.Exit(1)
	}

	js := res.JSON(*indent)
	if *out != "" {
		if err := os.WriteFile(*out, []byte(js), 0o644); err != nil {
			fmt.Fprintln(os.Stderr, "写文件失败:", err)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "已写入 %s（%d 字节）\n", *out, len(js))
	} else {
		fmt.Println(js)
	}

	// 摘要便于肉眼核对
	fmt.Fprintf(os.Stderr, "地图=%s 时长=%ds 回合=%d 经济=%v 热身跳过=%v 玩家=%d\n",
		res.MapName, res.DurationSec, len(res.Rounds), res.HasEconomy, res.WarmupSkipped, len(res.Players))
	for _, p := range res.Players {
		fmt.Fprintf(os.Stderr, "  %-20s %-2s R%-3d %2d/%2d/%2d HS%d dmg=%-5d KAST=%-3d surv=%-3d multi=%-3d ok=%d od=%d ★%d\n",
			trunc(p.Name, 20), p.Team, p.Rounds, p.Kills, p.Assists, p.Deaths, p.HeadshotKills,
			p.Damage, p.KastRounds, p.SurvivedRounds, p.MultiKillRounds,
			p.OpeningKills, p.OpeningDeaths, p.MVPs)
	}
}

func trunc(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}
