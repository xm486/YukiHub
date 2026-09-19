#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
release_sync.py —— YukiHub：GitCode → GitHub 的 Release **单向**同步工具
======================================================================

方向已确定：**只从 GitCode 同步到 GitHub**（单向）。本工具**永远不会**写 GitCode。

两个子命令：
  check     只读巡检，打印两边差异（默认命令，零副作用）
  backfill  GitCode → GitHub 回填（默认 dry-run，必须显式 --apply 才真写）

用法：
  # 1) 巡检（只读，不需要任何 token）
  python3 tools/release_sync.py check
  python3 tools/release_sync.py check --json

  # 2) 看回填计划（dry-run，仍然不写）
  python3 tools/release_sync.py backfill
  python3 tools/release_sync.py backfill --only-tag v0.1.4

  # 3) 真回填（需要 token）
  export GITCODE_TOKEN=xxx     # 下载 GitCode 附件用
  export GH_TOKEN=xxx          # 建 tag/release、上传附件用
  python3 tools/release_sync.py backfill --only-tag v0.1.4 --apply

环境变量：
  GITCODE_TOKEN   GitCode 个人访问令牌（**下载附件必需**：匿名直链 401）
  GH_TOKEN        GitHub 令牌（contents:write），也可用 GITHUB_TOKEN

安全设计：
  * 令牌只从环境变量读，绝不写入文件；所有输出经 mask()
  * 除 GitHub 侧的「建 release / 传附件」外，**没有任何写操作**
  * 代码里不存在 DELETE 任何远端资源的路径
  * --apply 缺省关闭；没带 GH_TOKEN 时直接拒绝执行
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------
OWNER = "xm486"
REPO = "YukiHub"

GH_API = "https://api.github.com"
GH_UPLOADS = "https://uploads.github.com"
GC_API = "https://api.gitcode.com/api/v5"

UA = "YukiHub-release-sync/0.2"
PER_PAGE = 100
TIMEOUT = 30
RETRIES = 3

# 实测（2026-09-19）：GitCode release 附件**匿名即可下载**
#   GET → 302 → file-cdn 签名链接 → 200/206（前 2 字节 = "PK"，确为 APK）
#   之前的「401」是 HEAD 请求造成的假象。Bearer / PRIVATE-TOKEN 仅作兜底。
GC_AUTH_STRATEGIES = ("anonymous", "bearer", "private-token", "query")
_gc_auth_cache = None


def mask(s):
    """把令牌掩码，避免进日志。"""
    if not s:
        return s
    s = str(s)
    for env in ("GITCODE_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"):
        t = os.environ.get(env)
        if t and len(t) > 6 and t in s:
            s = s.replace(t, t[:3] + "***MASKED***" + t[-2:])
    return s


def log(msg):
    print(mask(msg), flush=True)


# ----------------------------------------------------------------------------
# HTTP（JSON 接口用 urllib；大文件传输用 curl，见下方 curl_* 函数）
# ----------------------------------------------------------------------------
def http_json(method, url, token=None, payload=None, accept="application/json",
              retries=RETRIES, timeout=TIMEOUT):
    """返回 (status, data)；data 为解析后的 JSON / 文本片段 / None。"""
    body = None
    headers = {"Accept": accept, "User-Agent": UA}
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token

    last_status, last_data = 0, ""
    for attempt in range(retries):
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                try:
                    return resp.status, json.loads(raw)
                except json.JSONDecodeError:
                    return resp.status, raw
        except urllib.error.HTTPError as e:
            txt = ""
            try:
                txt = e.read().decode("utf-8", "replace")[:500]
            except Exception:
                pass
            last_status, last_data = e.code, txt
            if e.code in (403, 429, 500, 502, 503) and attempt < retries - 1:
                time.sleep(2 * (attempt + 1))
                continue
            return e.code, txt
        except Exception as e:
            last_status, last_data = 0, mask(str(e))
            if attempt < retries - 1:
                time.sleep(1.5 * (attempt + 1))
                continue
    return last_status, last_data


def http_get(url, token=None, accept="application/json"):
    return http_json("GET", url, token=token, accept=accept)


def fetch_paged(base_url, path, token=None):
    out, page = [], 1
    while True:
        url = "{}{}?{}".format(
            base_url, path, urllib.parse.urlencode({"per_page": PER_PAGE, "page": page})
        )
        status, data = http_get(url, token=token)
        if status != 200 or not isinstance(data, list):
            return out, (status, mask(data))
        out.extend(data)
        if len(data) < PER_PAGE:
            return out, None
        page += 1
        if page > 20:
            return out, None


# ----------------------------------------------------------------------------
# curl（大文件；令牌通过 stdin 配置传入，不出现在 argv 里）
# ----------------------------------------------------------------------------
def have_curl():
    from shutil import which
    return which("curl") is not None


def _run_curl(config_lines, timeout=3600):
    lines = list(config_lines) + ['max-time = "%d"' % timeout]
    p = subprocess.run(
        ["curl", "-K", "-"],
        input="\n".join(lines) + "\n",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return p.returncode, p.stdout or "", p.stderr or ""


def _parse_meta(out):
    status = 0
    size = 0
    m = re.search(r"HTTP=(\d+)", out)
    if m:
        status = int(m.group(1))
    m = re.search(r"SIZE=(\d+)", out)
    if m:
        size = int(m.group(1))
    return status, size


def curl_download(url, dest, header_lines=(), timeout=1800):
    """下载到 dest，返回 (returncode, http_status, size, err)。"""
    cfg = [
        'url = "%s"' % url,
        "location",
        "silent",
        "show-error",
        'retry = "4"',
        'retry-delay = "3"',
        # 卡死保护：速度低于 2KB/s 且持续 90 秒 → 判定卡死并失败
        # （GitCode 的 CDN 首包可能要十几秒，所以阈值不能太激进）
        'speed-limit = "2048"',
        'speed-time = "90"',
        'output = "%s"' % dest,
        'write-out = "HTTP=%{http_code} SIZE=%{size_download}"',
    ]
    cfg[1:1] = list(header_lines)
    rc, out, err = _run_curl(cfg, timeout=timeout)
    status, size = _parse_meta(out)
    return rc, status, size, mask(err)


def curl_upload(url, file_path, header_lines=(), timeout=3600):
    """POST 上传文件，返回 (returncode, http_status, err)。"""
    cfg = [
        'url = "%s"' % url,
        'request = "POST"',
        'upload-file = "%s"' % file_path,
        "location",
        "silent",
        "show-error",
        'write-out = "HTTP=%{http_code} SIZE=%{size_upload}"',
    ]
    cfg[1:1] = list(header_lines)
    rc, out, err = _run_curl(cfg, timeout=timeout)
    status, _ = _parse_meta(out)
    return rc, status, mask(err)


# ----------------------------------------------------------------------------
# GitCode 附件下载：三种鉴权位自动探测
# ----------------------------------------------------------------------------
def gc_header_lines(strategy, token):
    if strategy == "bearer" and token:
        return ['header = "Authorization: Bearer %s"' % token]
    if strategy == "private-token" and token:
        return ['header = "PRIVATE-TOKEN: %s"' % token]
    return []


def gc_url_with_auth(url, strategy, token):
    if strategy == "query" and token:
        sep = "&" if "?" in url else "?"
        return url + sep + "access_token=" + urllib.parse.quote(token)
    return url


def detect_gc_auth(probe_url, gc_token):
    """探测能下载附件的方式。用 1KB range 试，非常轻量。

    成功判定：HTTP 200 或 206（range 请求正常返回 206）。
    顺序：anonymous → bearer → private-token → query（未设 token 时自动跳过 token 类）。
    """
    global _gc_auth_cache
    if _gc_auth_cache is not None:
        return _gc_auth_cache
    for strategy in GC_AUTH_STRATEGIES:
        if strategy != "anonymous" and not gc_token:
            continue
        url = gc_url_with_auth(probe_url, strategy, gc_token)
        cfg = [
            'url = "%s"' % url,
            "location",
            "silent",
            "show-error",
            'range = "0-1023"',
            'output = "/dev/null"',
            'write-out = "HTTP=%{http_code}"',
        ] + gc_header_lines(strategy, gc_token)
        rc, out, err = _run_curl(cfg, timeout=150)
        status, _ = _parse_meta(out)
        if status in (200, 206):
            log("   GitCode 附件下载方式：{} ✅".format(strategy))
            _gc_auth_cache = strategy
            return strategy
        log("   尝试 {} → HTTP {}（未成功）".format(strategy, status or "?"))
    return None


# ----------------------------------------------------------------------------
# 归一化
# ----------------------------------------------------------------------------
def norm_github(rel):
    return {
        "tag": rel.get("tag_name"),
        "name": rel.get("name") or "",
        "body": rel.get("body") or "",
        "created_at": rel.get("created_at"),
        "prerelease": bool(rel.get("prerelease")),
        "draft": bool(rel.get("draft")),
        "id": rel.get("id"),
        "assets": [
            {"name": a.get("name"), "size": a.get("size"), "url": a.get("browser_download_url")}
            for a in (rel.get("assets") or [])
        ],
    }


def norm_gitcode(rel):
    return {
        "tag": rel.get("tag_name"),
        "name": rel.get("name") or "",
        "body": rel.get("body") or "",
        "created_at": rel.get("created_at"),
        "prerelease": bool(rel.get("prerelease")),
        "release_status": rel.get("release_status"),
        "commitish": rel.get("target_commitish"),
        "assets": [
            {"name": a.get("name"), "type": a.get("type"), "url": a.get("browser_download_url")}
            for a in (rel.get("assets") or [])
        ],
    }


def only_attach(assets):
    """GitCode：只保留真实附件（排除自动生成的源码包）。"""
    return [a for a in assets if a.get("type") != "source"]


def vkey(t):
    parts = []
    for chunk in (t or "").lstrip("v").split("."):
        parts.append(int(chunk) if chunk.isdigit() else 0)
    while len(parts) < 4:
        parts.append(0)
    return tuple(parts)


# ----------------------------------------------------------------------------
# check：只读巡检
# ----------------------------------------------------------------------------
def fmt_size(n):
    if n is None:
        return "未知"
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "{:.1f}{}".format(n, unit) if unit != "B" else "{}B".format(int(n))
        n /= 1024.0


def build_report(gh_list, gc_list, only_tag=None):
    gh_by_tag = {r["tag"]: r for r in gh_list}
    gc_by_tag = {r["tag"]: r for r in gc_list}
    if only_tag:
        gh_by_tag = {k: v for k, v in gh_by_tag.items() if k == only_tag}
        gc_by_tag = {k: v for k, v in gc_by_tag.items() if k == only_tag}

    only_gc = sorted([t for t in gc_by_tag if t not in gh_by_tag], key=vkey)
    only_gh = sorted([t for t in gh_by_tag if t not in gc_by_tag], key=vkey)
    both = sorted([t for t in gc_by_tag if t in gh_by_tag], key=vkey)

    detail = {}
    for t in only_gc:
        gc = gc_by_tag[t]
        att = only_attach(gc["assets"])
        detail[t] = {
            "tag": t,
            "name": gc["name"],
            "prerelease": gc["prerelease"],
            "commit": gc.get("commitish"),
            "assets": [{"name": a["name"], "size": None, "url": a["url"]} for a in att],
            "body_len": len(gc["body"]),
        }
    for t in only_gh:
        gh = gh_by_tag[t]
        detail[t] = {
            "tag": t,
            "name": gh["name"],
            "prerelease": gh["prerelease"],
            "commit": None,
            "assets": [{"name": a["name"], "size": a["size"], "url": a["url"]} for a in gh["assets"]],
            "body_len": len(gh["body"]),
        }

    mismatch = []
    for t in both:
        gh, gc = gh_by_tag[t], gc_by_tag[t]
        issues = []
        if gh["name"] != gc["name"]:
            issues.append("name 不同")
        if gh["body"] != gc["body"]:
            issues.append("正文不同（{} vs {} 字符）".format(len(gh["body"]), len(gc["body"])))
        if gh["prerelease"] != gc["prerelease"]:
            issues.append("prerelease 标记不同")
        gn = sorted(a["name"] for a in gh["assets"])
        cn = sorted(a["name"] for a in only_attach(gc["assets"]))
        if gn != cn:
            issues.append("附件名不同：GH={} / GC={}".format(gn, cn))
        if issues:
            mismatch.append({"tag": t, "issues": issues})

    return {
        "github_total": len(gh_by_tag),
        "gitcode_total": len(gc_by_tag),
        "only_gitcode": only_gc,
        "only_github": only_gh,
        "both": both,
        "detail": detail,
        "mismatch": mismatch,
    }


def print_report(rep, gh_err=None, gc_err=None):
    line = "=" * 66
    print(line)
    print(" YukiHub Release 巡检报告（只读 · 方向：GitCode → GitHub）")
    print(line)
    print(" GitHub  : {} 个 release".format(rep["github_total"]))
    print(" GitCode : {} 个 release".format(rep["gitcode_total"]))
    if gh_err:
        print(" ⚠️ GitHub 拉取异常：{}".format(gh_err))
    if gc_err:
        print(" ⚠️ GitCode 拉取异常：{}".format(gc_err))

    print()
    print("【A】GitCode 有、GitHub 没有 → 待回填（本次同步目标）")
    if not rep["only_gitcode"]:
        print("    （无，已同步）")
    for t in rep["only_gitcode"]:
        d = rep["detail"][t]
        att = ", ".join(a["name"] for a in d["assets"]) or "无附件"
        print("    - {:<10} {}".format(t, d["name"]))
        print("        commit  : {}".format((d["commit"] or "?")[:12]))
        print("        附件    : {}".format(att))
        print("        正文    : {} 字符".format(d["body_len"]))

    print()
    print("【B】GitHub 有、GitCode 没有（{} 个）→ 单向模式下**不处理**，仅记录".format(len(rep["only_github"])))
    for t in rep["only_github"]:
        d = rep["detail"][t]
        att = ", ".join(
            "{} ({})".format(a["name"], fmt_size(a["size"])) for a in d["assets"]
        ) or "无附件"
        print("    - {:<10} {}".format(t, att))

    print()
    if not rep["both"]:
        print("【C】两边交集：0 个（两边 release **完全没有重叠**）")
    else:
        print("【C】两边都有（{} 个）：{}".format(len(rep["both"]), ", ".join(rep["both"])))
        if rep["mismatch"]:
            print("    存在不一致：")
            for m in rep["mismatch"]:
                print("    - {:<10} {}".format(m["tag"], "；".join(m["issues"])))
        else:
            print("    内容一致 ✅")
    print(line)


def cmd_check(args):
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    gc_token = os.environ.get("GITCODE_TOKEN")
    gh_raw, gh_err = fetch_paged(GH_API, "/repos/{}/{}/releases".format(OWNER, REPO), token=gh_token)
    gc_raw, gc_err = fetch_paged(GC_API, "/repos/{}/{}/releases".format(OWNER, REPO), token=gc_token)
    gh_list = [norm_github(r) for r in gh_raw if isinstance(r, dict)]
    gc_list = [norm_gitcode(r) for r in gc_raw if isinstance(r, dict)]
    rep = build_report(gh_list, gc_list, only_tag=args.only_tag)
    if args.json:
        print(json.dumps(rep, ensure_ascii=False, indent=2))
    else:
        print_report(rep, gh_err=gh_err, gc_err=gc_err)
    if not gh_list and not gc_list:
        return 2
    return 0


# ----------------------------------------------------------------------------
# backfill：GitCode → GitHub（写操作，默认 dry-run）
# ----------------------------------------------------------------------------
def apk_target_name(name, mode):
    """附件命名策略：keep=保持 GitCode 原名；github=补上 v（YukiHub_v0.2.6.1.apk）"""
    if mode != "github":
        return name
    m = re.match(r"^(YukiHub_)(\d.*)$", name or "")
    if m:
        return m.group(1) + "v" + m.group(2)
    return name


def gh_release_by_tag(tag, token):
    url = "{}/repos/{}/{}/releases/tags/{}".format(
        GH_API, OWNER, REPO, urllib.parse.quote(tag)
    )
    status, data = http_get(url, token=token)
    if status == 200 and isinstance(data, dict):
        return data
    return None


def gh_assets_of(release_id, token):
    url = "{}/repos/{}/{}/releases/{}/assets?per_page=100".format(
        GH_API, OWNER, REPO, release_id
    )
    status, data = http_get(url, token=token)
    if status == 200 and isinstance(data, list):
        return data
    return []


def gh_can_write(token):
    """只读探测：当前令牌对该仓库是否有写权限（用 repo 接口的 permissions 字段）。

    返回 (True/False/None, 详情)。403 那种「Resource not accessible by integration」
    靠这个能提前发现，而不是等跑到一半才炸。
    """
    url = "{}/repos/{}/{}".format(GH_API, OWNER, REPO)
    status, data = http_get(url, token=token)
    if status != 200 or not isinstance(data, dict):
        return None, "HTTP {}".format(status)
    perms = data.get("permissions") or {}
    return bool(perms.get("push")), perms


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def looks_like_zip(path):
    try:
        with open(path, "rb") as f:
            return f.read(4)[:2] == b"PK"
    except Exception:
        return False


def cmd_probe(args):
    """只读探测：验证能否下载 GitCode release 附件（每个请求仅 1KB，不写任何东西）。"""
    gc_token = os.environ.get("GITCODE_TOKEN")

    gc_raw, gc_err = fetch_paged(GC_API, "/repos/{}/{}/releases".format(OWNER, REPO), token=None)
    if gc_err or not gc_raw:
        log("❌ 无法读取 GitCode releases：{}".format(gc_err))
        return 2

    probe_url = probe_tag = None
    for r in gc_raw:
        if not isinstance(r, dict):
            continue
        atts = [
            a for a in (r.get("assets") or [])
            if a.get("type") != "source" and a.get("browser_download_url")
        ]
        if atts:
            probe_url = atts[0]["browser_download_url"]
            probe_tag = r.get("tag_name")
            break

    if not probe_url:
        log("❌ GitCode 侧找不到可探测的附件")
        return 2

    global _gc_auth_cache
    _gc_auth_cache = None

    print("=" * 66)
    print(" GitCode 附件下载通路探测（只读 · 每个请求只取 1KB）")
    print("=" * 66)
    print(" 探测对象 : {} / {}".format(probe_tag, probe_url.rsplit("/", 1)[-1]))
    print(" 令牌     : {}".format(
        "已设置（{} 字符，不显示内容）".format(len(gc_token)) if gc_token else "未设置（走匿名，通常够用）"))
    print("-" * 66)

    for strategy in GC_AUTH_STRATEGIES:
        if strategy != "anonymous" and not gc_token:
            print("  {:<14} → 跳过（未设 token）".format(strategy))
            continue
        url = gc_url_with_auth(probe_url, strategy, gc_token)
        cfg = [
            'url = "%s"' % url,
            "location", "silent", "show-error",
            'range = "0-1023"',
            'output = "/dev/null"',
            'write-out = "HTTP=%{http_code}"',
        ] + gc_header_lines(strategy, gc_token)
        rc, out, err = _run_curl(cfg, timeout=150)
        status, _ = _parse_meta(out)
        ok = status in (200, 206)
        print("  {:<14} → HTTP {:<4} {}".format(
            strategy, status or "?", "✅ 可用" if ok else "❌ 不可用"))
        if ok and _gc_auth_cache is None:
            _gc_auth_cache = strategy

    print("-" * 66)
    if _gc_auth_cache == "anonymous":
        print(" 结论：**匿名即可下载 —— 不需要任何 GitCode 令牌** ✅")
    elif _gc_auth_cache:
        print(" 结论：匿名不通，但 `{}` 可用（回填会自动采用）".format(_gc_auth_cache))
    else:
        print(" 结论：所有通路都不通。可能原因：")
        print("   1) 网络/DNS 到 file-cdn.gitcode.com 不通")
        print("   2) 项目被设为私有或附件被限制")
        print("   3) token 无效（但匿名本应可通过，先查前两条）")
    print("=" * 66)
    return 0 if _gc_auth_cache else 1


def cmd_backfill(args):
    gh_token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    gc_token = os.environ.get("GITCODE_TOKEN")

    if args.apply and not gh_token:
        log("❌ --apply 需要 GH_TOKEN（或 GITHUB_TOKEN）环境变量。已中止。")
        return 3
    if not have_curl():
        log("❌ 未找到 curl，无法进行大文件传输。已中止。")
        return 4

    # 写权限预检：403 那种坑提前拦住
    if args.apply:
        can_write, info = gh_can_write(gh_token)
        if can_write is False:
            log("❌ 当前令牌对 {}/{} 没有写权限（permissions={}）。已中止。".format(OWNER, REPO, info))
            log("   解决二选一：")
            log("     A) 仓库 Settings → Actions → General → Workflow permissions")
            log("        → 选 'Read and write permissions' → Save，然后重跑")
            log("     B) 建一个有 contents 读写权限的 PAT，存成 Secret `SYNC_GH_TOKEN`")
            log("        （工作流会自动优先使用它）")
            return 5
        if can_write is True:
            log("   写权限预检：✅ 通过（permissions={}）".format(info))
        else:
            log("   ⚠️ 写权限预检无法判定（{}），继续执行".format(info))

    # 拉两边现状
    gh_raw, gh_err = fetch_paged(GH_API, "/repos/{}/{}/releases".format(OWNER, REPO), token=gh_token)
    gc_raw, gc_err = fetch_paged(GC_API, "/repos/{}/{}/releases".format(OWNER, REPO), token=gc_token)
    if gc_err or not gc_raw:
        log("❌ 无法读取 GitCode releases：{}".format(gc_err))
        return 2
    gh_list = [norm_github(r) for r in gh_raw if isinstance(r, dict)]
    gc_list = [norm_gitcode(r) for r in gc_raw if isinstance(r, dict)]
    gh_tags = {r["tag"] for r in gh_list}

    targets = [r for r in gc_list if r["tag"] not in gh_tags]
    if args.only_tag:
        targets = [r for r in targets if r["tag"] == args.only_tag]
    targets.sort(key=lambda r: vkey(r["tag"]))
    if args.limit:
        targets = targets[: args.limit]

    print("=" * 66)
    print(" 回填计划（GitCode → GitHub · 单向）")
    print("=" * 66)
    print(" 目标仓库 : {}/{}（GitHub）".format(OWNER, REPO))
    print(" 待处理   : {} 个 release".format(len(targets)))
    print(" 模式     : {}".format("APPLY（真写）" if args.apply else "DRY-RUN（不写，仅展示）"))
    print(" 附件     : {}".format("跳过" if args.skip_assets else "搬（命名策略：{}）".format(args.asset_mode)))
    print("-" * 66)
    if not targets:
        print(" 没有需要回填的版本 ✅")
        return 0

    for i, r in enumerate(targets, 1):
        att = only_attach(r["assets"])
        print(" [{}/{}] {}  ← {}".format(i, len(targets), r["tag"], (r.get("commitish") or "?")[:12]))
        print("        name  : {}".format(r["name"]))
        print("        正文  : {} 字符".format(len(r["body"])))
        for a in att:
            print("        附件  : {} → {}".format(a["name"], apk_target_name(a["name"], args.asset_mode)))
        if not att:
            print("        附件  : （GitCode 侧无真实附件）")
    print("-" * 66)

    if not args.apply:
        print(" ※ 这是 DRY-RUN：未做任何写操作。")
        print("   确认无误后，加 --apply 真跑（建议先 --only-tag v0.1.4 单版本试水）")
        print("=" * 66)
        return 0

    # ---- 真正执行 ----
    tmpdir = tempfile.mkdtemp(prefix="yukihub-rel-")
    ok_cnt = skip_cnt = fail_cnt = 0
    failures = []
    auth_strategy = None

    try:
        for i, r in enumerate(targets, 1):
            tag = r["tag"]
            print()
            log("▶ [{}/{}] {} 开始".format(i, len(targets), tag))

            # 1) 建 / 找 release
            rel = gh_release_by_tag(tag, gh_token)
            if rel:
                rid = rel.get("id")
                log("   release 已存在（id={}），跳过创建".format(rid))
                skip_cnt += 1
            else:
                payload = {
                    "tag_name": tag,
                    "target_commitish": r.get("commitish"),
                    "name": r["name"] or tag,
                    "body": r["body"] or "",
                    "draft": False,
                    "prerelease": bool(r["prerelease"]),
                }
                status, data = http_json(
                    "POST",
                    "{}/repos/{}/{}/releases".format(GH_API, OWNER, REPO),
                    token=gh_token,
                    payload=payload,
                )
                if status not in (200, 201):
                    log("   ❌ 创建 release 失败 HTTP {}：{}".format(status, data))
                    if status == 403 and "not accessible by integration" in str(data):
                        log("   💡 这是**令牌权限**问题，不是脚本问题。二选一：")
                        log("      A) 仓库 Settings → Actions → General → Workflow permissions")
                        log("         → 选 'Read and write permissions' → Save，然后重跑")
                        log("      B) 建一个 contents 读写的 PAT，存成 Secret `SYNC_GH_TOKEN`")
                        log("         （工作流会自动优先使用它，无需改代码）")
                    failures.append((tag, "create-release HTTP {}".format(status)))
                    fail_cnt += 1
                    continue
                rid = data.get("id")
                log("   ✅ release 已创建（id={}，tag 自动建立）".format(rid))
                ok_cnt += 1

            if args.skip_assets:
                continue

            # 2) 附件
            atts = only_attach(r["assets"])
            if not atts:
                continue
            existing = {a.get("name") for a in gh_assets_of(rid, gh_token)}
            for a in atts:
                want = apk_target_name(a["name"], args.asset_mode)
                if want in existing:
                    log("   ⏭ 附件已存在：{}".format(want))
                    continue
                src = a.get("url")
                if not src:
                    log("   ⚠️ 附件无下载地址，跳过：{}".format(a["name"]))
                    failures.append((tag, "no-asset-url {}".format(a["name"])))
                    continue

                # 探测下载方式（只需一次；匿名优先，未设 token 也能过）
                if auth_strategy is None:
                    auth_strategy = detect_gc_auth(src, gc_token)
                    if auth_strategy is None:
                        log("   ❌ 下载通路全部失败（匿名 + token 都没通）")
                        failures.append((tag, "no-download-route"))
                        fail_cnt += 1
                        break

                dest = os.path.join(tmpdir, want.replace("/", "_"))
                log("   ↓ 下载 {} …".format(a["name"]))
                rc, st, size, err = curl_download(
                    gc_url_with_auth(src, auth_strategy, gc_token),
                    dest,
                    header_lines=gc_header_lines(auth_strategy, gc_token),
                )
                if rc != 0 or st not in (200, 206) or not os.path.exists(dest):
                    log("   ❌ 下载失败 rc={} HTTP={} {}".format(rc, st, err))
                    failures.append((tag, "download HTTP {}".format(st)))
                    fail_cnt += 1
                    continue
                if size < 1024 * 100 or not looks_like_zip(dest):
                    log("   ❌ 下载内容异常（{} 字节，非 zip）—— 疑似鉴权失败返回的 JSON".format(size))
                    failures.append((tag, "bad-payload"))
                    fail_cnt += 1
                    continue
                digest = sha256_of(dest)
                log("     下载完成 {}  sha256={}…".format(fmt_size(size), digest[:16]))

                # 上传
                up_url = "{}/repos/{}/{}/releases/{}/assets?name={}".format(
                    GH_UPLOADS, OWNER, REPO, rid, urllib.parse.quote(want)
                )
                headers = ['header = "Authorization: Bearer %s"' % gh_token,
                           'header = "Content-Type: application/vnd.android.package-archive"']
                log("   ↑ 上传到 GitHub …")
                rc2, st2, err2 = curl_upload(up_url, dest, header_lines=headers)
                if rc2 != 0 or st2 not in (200, 201):
                    log("   ❌ 上传失败 rc={} HTTP={} {}".format(rc2, st2, err2))
                    failures.append((tag, "upload HTTP {}".format(st2)))
                    fail_cnt += 1
                else:
                    log("   ✅ 附件完成：{}（{}）".format(want, fmt_size(size)))
                    ok_cnt += 1
                try:
                    os.remove(dest)
                except OSError:
                    pass
    finally:
        import shutil as _sh
        _sh.rmtree(tmpdir, ignore_errors=True)

    print()
    print("=" * 66)
    print(" 回填结果：成功 {} / 跳过 {} / 失败 {}".format(ok_cnt, skip_cnt, fail_cnt))
    if failures:
        print(" 失败明细：")
        for tag, why in failures:
            print("   - {:<10} {}".format(tag, why))
    print("=" * 66)
    return 1 if fail_cnt else 0


# ----------------------------------------------------------------------------
# main
# ----------------------------------------------------------------------------
def main():
    argv = sys.argv[1:]
    sub = argv[0] if argv and not argv[0].startswith("-") else "check"
    rest = argv[1:] if (argv and not argv[0].startswith("-")) else argv

    if sub == "check":
        ap = argparse.ArgumentParser(prog="release_sync.py check", description="只读巡检")
        ap.add_argument("--json", action="store_true")
        ap.add_argument("--only-tag", default=None)
        return cmd_check(ap.parse_args(rest))

    if sub == "probe":
        ap = argparse.ArgumentParser(prog="release_sync.py probe", description="附件鉴权探测（只读）")
        ap.parse_args(rest)
        return cmd_probe(None)

    if sub == "backfill":
        ap = argparse.ArgumentParser(prog="release_sync.py backfill", description="GitCode → GitHub 回填")
        ap.add_argument("--only-tag", default=None, help="只处理某个 tag")
        ap.add_argument("--limit", type=int, default=0, help="最多处理几个（0=不限）")
        ap.add_argument("--apply", action="store_true", help="真写（默认 dry-run）")
        ap.add_argument("--skip-assets", action="store_true", help="只建 release，不搬附件")
        ap.add_argument("--asset-mode", choices=("keep", "github"), default="keep",
                        help="附件命名：keep=原样（默认）/ github=补 v")
        return cmd_backfill(ap.parse_args(rest))

    print("用法：release_sync.py [check|backfill] [选项]")
    print("  check     只读巡检（默认）")
    print("  backfill  GitCode → GitHub 回填（默认 dry-run）")
    return 1


if __name__ == "__main__":
    sys.exit(main())