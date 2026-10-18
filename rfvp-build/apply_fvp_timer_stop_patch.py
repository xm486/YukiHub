#!/usr/bin/env python3
"""YukiHub 补丁：timer 到点即停（`TimerGet` 归零），修老 FVP 按钮点不动。

来源
----
移植自上游 xmoezzz/rfvp 提交 `a1091393`（"fix(timer): stop a timer once it
reaches its resolution"，2026-10-18，closes issue #49）。

背景
----
原引擎（IDA `sub_412430` / `sub_436EC0`）在 timer 的 elapsed 超过 resolution
时，会把 elapsed **夹到 resolution** 并 **关闭该 timer**；此后
`TimerGet(id, nil)` 走 `enabled == false` 分支，返回 **0**。

rfvp 此前只做 `saturating_add`，elapsed 一直涨、timer 一直 enabled，
`TimerGet` 永远不会返回 0。

后果：老 FVP 作品的存档按钮靠 `TimerGet(1) == 0` 判断"空闲"才响应，
条件永远不成立 → 按钮点不动，点击穿透到文字推进。

修复
----
`TimerManager::tick()` 内，在累加之后夹取：

    if item.elapsed > item.resolution {
        item.elapsed = item.resolution;
        item.enabled = false;
    }

附带上游同提交里的回归测试（`a_timer_stops_at_its_resolution`）。

行为影响
--------
- `TimerGet(id, nil)`：到点后返回 0（原引擎语义），不再返回无限增长的值。
- `TimerGet(id, default)`：到点后返回 `default`（`enabled == false` 分支）。
- resolution 未设置（=0）的 timer：第一帧 tick 就停。这与上游一致，
  且顺带避开了 `TimerGet` 里 `elapsed / resolution` 的除零。

注意
----
本补丁**不**修"点击穿透"本身 —— 穿透是 `apply_text_click_patch.py` 解决的。
本补丁只是让按钮的脚本条件能成立。
"""
import sys
from pathlib import Path

TIMER = "crates/rfvp/src/subsystem/resources/timer_manager.rs"

MARKER = "YHFVP-TIMERSTOP-1.0"

TIMER_OLD = """                item.elapsed = item.elapsed.saturating_add(delta_ms);
            }
        }
    }
}"""

TIMER_NEW = """                item.elapsed = item.elapsed.saturating_add(delta_ms);
                // YukiHub: 到点即停（对齐原引擎 sub_412430 / sub_436EC0）。
                // elapsed 夹到 resolution 并关掉 timer，此后 `TimerGet(id, nil)` 读回 0。
                // 老 FVP 的存档按钮依赖 `TimerGet(1) == 0` 才会响应（issue #49）。
                if item.elapsed > item.resolution {
                    item.elapsed = item.resolution;
                    item.enabled = false;
                }
            }
        }
    }
}

/// YukiHub patch marker — CI greps the built .so for this string to prove the patch landed.
#[no_mangle]
pub static YHFVP_TIMER_STOP_MARKER: [u8; 19] = *b"YHFVP-TIMERSTOP-1.0";

#[cfg(all(test, not(feature = "no_std")))]
mod tests {
    use super::TimerManager;

    #[test]
    fn a_timer_stops_at_its_resolution() {
        let mut timers = TimerManager::new();
        timers.set_elapsed(1, 0);
        timers.set_resolution(1, 1000);
        timers.set_enabled(1, true);
        timers.tick(600);
        assert!(timers.get_enabled(1));
        assert_eq!(timers.get_elapsed(1), 600);
        timers.tick(600);
        assert!(!timers.get_enabled(1));
        assert_eq!(timers.get_elapsed(1), 1000);
    }
}"""


def patch_timer(path: Path) -> int:
    text = path.read_text(encoding="utf-8")

    if MARKER in text:
        print(f"[OK]   {TIMER}: 已应用（幂等）")
        return 0

    n = text.count(TIMER_OLD)
    if n != 1:
        print(f"[FAIL] {TIMER}: 锚点 tick() 尾部命中 {n} 次（期望 1）—— 上游源码已变动")
        return 1

    text = text.replace(TIMER_OLD, TIMER_NEW, 1)
    path.write_text(text, encoding="utf-8")
    print(f"[OK]   {TIMER}: 到点即停 + 回归测试已应用")
    return 0


def main() -> int:
    p = Path(TIMER)
    if not p.exists():
        # fail-fast：上游若重命名/移动了该文件，这里必须让 CI 红，而不是静默跳过。
        print(f"[FAIL] {TIMER} 不存在 —— 上游可能改了目录结构，补丁无法应用")
        return 1
    return patch_timer(p)


if __name__ == "__main__":
    sys.exit(main())
