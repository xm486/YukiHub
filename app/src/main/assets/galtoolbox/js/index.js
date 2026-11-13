// 雪花特效（canvas 版：1 个画布替代 50 个 DOM 节点，移动端更省显存/电量）
function createSnowflakeSprite() {
    const S = 32;
    const sp = document.createElement('canvas');
    sp.width = sp.height = S;
    const sc = sp.getContext('2d');
    const g = sc.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2);
    g.addColorStop(0, 'rgba(224, 247, 250, 1)');
    g.addColorStop(0.6, 'rgba(224, 247, 250, 0.92)');
    g.addColorStop(1, 'rgba(224, 247, 250, 0)');
    sc.fillStyle = g;
    sc.beginPath();
    sc.arc(S / 2, S / 2, S / 2, 0, Math.PI * 2);
    sc.fill();
    return sp;
}

// 兜底：环境不支持 canvas 时，沿用原来的 DOM 雪花
function createSnowflakesLegacy(container) {
    const count = 50;
    for (let i = 0; i < count; i++) {
        const flake = document.createElement('div');
        flake.className = 'snowflake';
        flake.style.left = `${Math.random() * 100}%`;
        flake.style.animationDuration = `${Math.random() * 10 + 5}s`;
        flake.style.opacity = `${Math.random() * 0.5 + 0.3}`;
        flake.style.width = `${Math.random() * 10 + 5}px`;
        flake.style.height = flake.style.width;
        container.appendChild(flake);
    }
}

function createSnowflakes() {
    const container = document.getElementById('snow-container');
    if (!container) return;

    // 尊重系统“减少动态效果”偏好
    const mq = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
    if (mq && mq.matches) return;

    const board = document.createElement('canvas');
    const ctx = board.getContext ? board.getContext('2d') : null;
    if (!ctx) { createSnowflakesLegacy(container); return; }

    const COUNT = 50;
    const DPR = Math.min(window.devicePixelRatio || 1, 2);
    const sprite = createSnowflakeSprite();
    let W = 0, H = 0, flakes = [];

    board.className = 'snow-canvas';
    board.style.display = 'block';
    board.style.width = '100%';
    board.style.height = '100%';
    container.appendChild(board);

    function sizeCanvas() {
        board.width = Math.round(W * DPR);
        board.height = Math.round(H * DPR);
        ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
    }

    function seed() {
        flakes = [];
        for (let i = 0; i < COUNT; i++) {
            const size = Math.random() * 10 + 5;    // 5-15px，与原版一致
            flakes.push({
                x: Math.random() * W,
                y: Math.random() * H,
                r: size / 2,
                alpha: Math.random() * 0.5 + 0.3,   // 0.3-0.8，与原版一致
                rate: 1 / (Math.random() * 10 + 5)  // 每秒下落多少屏，等价于 5-15s 落满一屏
            });
        }
    }

    function layout() {
        const nw = window.innerWidth;
        const nh = window.innerHeight;
        if (W === 0) {                       // 首次初始化
            W = nw; H = nh;
            sizeCanvas(); seed();
            return;
        }
        // 手机地址栏收起/展开只会小幅改变高度。
        // 这里必须忽略：否则上下滑动时地址栏动画会不断触发 resize，
        // 导致雪花被反复重新随机（看起来就是“闪一下 / 突然变快”）。
        if (nw === W && Math.abs(nh - H) <= 150) {
            H = nh;                          // 只更新画布尺寸，保留雪花位置
            sizeCanvas();
            return;
        }
        // 真正的尺寸变化（旋转屏幕/缩窗口）：按比例平移，保持连续性，不重新随机
        const sx = nw / W, sy = nh / H;
        for (let i = 0; i < flakes.length; i++) {
            flakes[i].x *= sx;
            flakes[i].y *= sy;
        }
        W = nw; H = nh;
        sizeCanvas();
    }

    layout();
    window.addEventListener('resize', layout);
    window.addEventListener('orientationchange', layout);

    let last = 0;
    function frame(ts) {
        let dt = last ? (ts - last) / 1000 : 0;   // 换算成秒
        last = ts;
        // 卡顿或从后台切回时不要“追帧”，否则雪花会瞬移一段距离
        if (dt > 0.1) dt = 0.1;
        ctx.clearRect(0, 0, W, H);
        for (let i = 0; i < COUNT; i++) {
            const f = flakes[i];
            f.y += f.rate * H * dt;
            if (f.y - f.r > H) {
                f.y = -f.r;
                f.x = Math.random() * W;
            }
            ctx.globalAlpha = f.alpha;
            ctx.drawImage(sprite, f.x - f.r * 1.6, f.y - f.r * 1.6, f.r * 3.2, f.r * 3.2);
        }
        ctx.globalAlpha = 1;
        requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
}

window.addEventListener('load', createSnowflakes);
		// 标签映射表缓存（避免每次查询都重建大对象）
		let __tagMapCache = null;

		// 配置项
		const CONFIG = {
			TRANSLATE_API: "https://api.pearktrue.cn/api/translate/ai"
		};

		// DOM元素
		const gameInput = document.getElementById("gameInput");
		const searchResultArea = document.getElementById("searchResultArea");
		const detailArea = document.getElementById("detailArea");
		const searchBtn = document.getElementById("searchBtn");
		const coverModal = document.getElementById("coverModal");
		const modalImage = document.getElementById("modalImage");
		const coverLoading = document.getElementById("coverLoading");
		const gameCover = document.getElementById("gameCover");

		// 时长映射
		const LENGTH_MAP = {
			1: "很短",
			2: "短",
			3: "中等",
			4: "长",
			5: "很长"
		};

		// 重置所有状态
		function resetAll() {
			gameInput.value = "";
			searchResultArea.innerHTML = "输入游戏名称，点击查询获取" + SOURCE_LABEL[CURRENT.source] + "结果～";
			searchResultArea.style.display = "none";
			detailArea.style.display = "none";
			closeModal();
		}

		// 智能清理文本格式，保留有意义的换行
		function cleanText(text) {
			if (!text) return "";
			
			// 先去掉首尾空白字符
			text = text.trim();
			
			// 处理不同的换行符：\r\n, \r, \n 统一为 \n
			text = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
			
			// 处理可能存在的HTML标签或特殊标记
			text = text.replace(/<\/?[^>]+(>|$)/g, ''); // 移除HTML标签
			text = text.replace(/\[.*?\]/g, ''); // 移除方括号内容
			
			// 处理连续的空格：多个空格变为一个
			text = text.replace(/[ \t]+/g, ' ');
			
			// 处理段落间的换行：保留有意义的分段
			// 先将连续的换行（2个或更多）替换为双换行（段落分隔）
			text = text.replace(/\n{3,}/g, '\n\n');
			
			// 处理行首空格
			text = text.replace(/^ +/gm, '');
			
			// 去掉尾随的换行
			text = text.replace(/\n+$/, '');
			
			return text;
		}

		// 翻译游戏简介 + 智能清理空白字符
		async function translateDescription(desc) {
			if (!desc) return "无简介";
			
			try {
				const pearkRes = await fetch(`${CONFIG.TRANSLATE_API}?text=${encodeURIComponent(desc)}`, {
				signal: AbortSignal.timeout(8000)
			});
			if (!pearkRes.ok) throw new Error(`翻译接口异常：${pearkRes.status}`);
				const pearkData = await pearkRes.json();
				let result = pearkData.data?.trim() || desc.trim();
				// 使用智能清理函数
				result = cleanText(result);
				return result;
			} catch (error) {
				console.error("翻译失败：", error);
				// 原始内容同样智能清理
				return cleanText(desc);
			}
		}

		// 封面放大功能
		function openModal(imageSrc) {
			modalImage.src = imageSrc;
			coverModal.classList.add("active");
			document.body.style.overflow = "hidden";
		}

		// 关闭封面放大
		function closeModal() {
			coverModal.classList.remove("active");
			modalImage.src = "";
			document.body.style.overflow = "auto";
		}

		// 点击遮罩层关闭
		coverModal.addEventListener("click", function(e) {
			if (e.target === coverModal) {
				closeModal();
			}
		});

		// ==================== 数据源适配层 ====================
		// 三个源统一成同一套接口，上层渲染只认统一结构，不关心是谁提供的。
		const SOURCE_LABEL = {
			vndb: "VNDB",
			nextmoe: "NextMoe"
		};

		const CURRENT = { source: "vndb" };

		// ---------- VNDB ----------
		const VNDB = {
			async search(keyword) {
				const res = await fetch("https://api.vndb.org/kana/vn", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({
						filters: ["search", "=", keyword],
						fields: "title,image.url,alttitle,released",
						count: true,
						results: 30
					})
				});
				if (!res.ok) throw new Error("VNDB 接口返回 " + res.status);
				const data = await res.json();
				return {
					total: data.count,
					items: (data.results || []).map(g => ({
						id: g.id,
						title: g.title,
						sub: g.released ? "发售 " + g.released : ""
					}))
				};
			},

			async detail(id) {
				const res = await fetch("https://api.vndb.org/kana/vn", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({
						filters: ["id", "=", id],
						fields: "title,image.url,alttitle,released,languages,platforms,description,length,length_minutes,developers.id,developers.name,tags{id,name,spoiler,rating},rating,votecount"
					})
				});
				if (!res.ok) throw new Error("VNDB 接口返回 " + res.status);
				const data = await res.json();
				const g = (data.results || [])[0];
				if (!g) throw new Error("未找到该游戏详情");
				const map = getTagMap();
				let rating = "无评分数据";
				if (g.rating) rating = (g.rating / 10).toFixed(1) + " 分（" + g.votecount + " 人评分）";
				let length = "未知";
				if (g.length) {
					const t = LENGTH_MAP[g.length] || "未知";
					length = g.length_minutes ? t + "（" + (g.length_minutes / 60).toFixed(1) + " 小时）" : t;
				}
				return {
					title: g.title,
					original: g.alttitle || "无原名",
					cover: (g.image && g.image.url) || "",
					rating: rating,
					length: length,
					released: g.released || "未知",
					languages: (g.languages || []).join("、") || "未知",
					platforms: (g.platforms || []).join("、") || "未知",
					developers: ((g.developers || []).map(d => d.name).join("、")) || "未知",
					id: g.id,
					desc: g.description,
					translate: true,
					tags: (g.tags || []).map(t => ({
						name: map[t.name] || t.name,
						spoiler: t.spoiler,
						rating: typeof t.rating === "number" ? t.rating : null
					}))
				};
			}
		};

		// ---------- NextMoe / 鲲 Galgame ----------
		const NEXTMOE_API = "https://api.nextmoe.dev/v2";
		const NEXTMOE = {
			async get(path) {
				const res = await fetch(NEXTMOE_API + path, {
					headers: { "Accept": "application/json" },
					signal: AbortSignal.timeout(12000)
				});
				if (!res.ok) throw new Error("NextMoe 接口返回 " + res.status);
				return res.json();
			},

			async search(keyword) {
				// 多次递增搜索：原词 → 去标点 → 逐字缩短。
				// 原因：NextMoe 正名常带「＊」「～」等符号（如「千恋＊万花」），
				// 用户输入通常没有，精确匹配会失败并退化成不相关结果。
				const variants = buildVariants(keyword);
				let hits = [];

				for (let vi = 0; vi < variants.length; vi++) {
					const q = encodeURIComponent(variants[vi]);
					// 不限制 nsfw —— galgame 绝大多数是 R18，过滤掉等于丢掉大半库
					const r = await this.get("/catalog/search?object=work&nsfw=true&limit=30&q=" + q);
					hits = (r.items || []).filter(x => (x.target_object || "work") === "work");
					if (hits.length) break;
				}

				// 结果太少时，用拆词后的核心词再补一轮，避免「千恋万花」这类漏召回
				if (hits.length < 3 && variants.length > 1) {
					try {
						const r2 = await this.get("/catalog/search?object=work&nsfw=true&limit=30&q=" +
							encodeURIComponent(variants[1]));
						const more = (r2.items || []).filter(x => (x.target_object || "work") === "work");
						const seen = {};
						hits.forEach(h => seen[h.id] = 1);
						more.forEach(h => { if (!seen[h.id]) { seen[h.id] = 1; hits.push(h); } });
					} catch (e) { /* 补召回失败忽略 */ }
				}

				hits = hits.slice(0, 30);
				if (!hits.length) return { total: 0, items: [] };

				// 搜索结果本身不带封面，按 ids 批量补一次（单次 <= 100）
				let coverMap = {};
				try {
					const ids = hits.map(x => x.id).join(",");
					const w = await this.get("/catalog/works?nsfw=true&include=covers&limit=100&ids=" + encodeURIComponent(ids));
					(w.items || []).forEach(x => {
						let u = (x.cover && x.cover.url) || "";
						if (!u && x.covers && x.covers.length) {
							const c = x.covers.find(v => v.url) || null;
							if (c) u = c.url;
						}
						if (u) coverMap[x.id] = u;
					});
				} catch (e) { /* 封面补失败不影响搜索 */ }

				return {
					total: hits.length,
					items: hits.map(x => ({
						id: x.id,
						title: pickTitle(x),
						sub: x.display_name,
						cover: coverMap[x.id] || ""
					}))
				};
			},

			async detail(id) {
				const r = await this.get("/catalog/works/" + encodeURIComponent(id) +
					"?nsfw=true&include=titles,intros,covers,companies,tags,ratings,playtimes");

				// 中文名：localized 最权威，其次 titles 里的 zh-Hans
				let cn = "";
				let orig = r.display_name || "";
				if (r.localized && r.localized["zh-Hans"]) cn = r.localized["zh-Hans"].value || "";
				if (!cn && r.titles) {
					const t = r.titles.find(x => x.lang === "zh-Hans" && x.title);
					if (t) cn = t.title;
				}

				// 简介：优先中文，其次原语言
				let zhDesc = "", jaDesc = "";
				(r.intros || []).forEach(x => {
					const txt = x.body || x.text || x.value || "";
					if (!txt) return;
					if (x.lang === "zh-Hans" || x.lang === "zh") { if (!zhDesc) zhDesc = txt; }
					else if (x.lang === r.olang || x.lang === "ja") { if (!jaDesc) jaDesc = txt; }
				});

				// 评分：多源并列，优先 VNDB -> Bangumi -> 批评空间
				let rating = "暂无评分";
				const rs = r.ratings || [];
				const pick = (src) => rs.find(x => x.source === src && x.score > 0);
				const vndbR = pick("vndb"), bgmR = pick("bangumi"), egsR = pick("erogamescape"), nmR = pick("nextmoe");
				if (vndbR) rating = vndbR.score.toFixed(1) + " 分（VNDB · " + vndbR.vote_count + " 票）";
				else if (bgmR) rating = bgmR.score.toFixed(1) + " 分（Bangumi · " + bgmR.vote_count + " 票）";
				else if (egsR) rating = egsR.score.toFixed(0) + " 分（批评空间 · " + egsR.vote_count + " 票）";
				else if (nmR) rating = nmR.score.toFixed(1) + " 分（本站 · " + nmR.vote_count + " 票）";

				// 时长
				let length = "未知";
				const pt = (r.playtimes || []).find(x => x.minutes > 0);
				if (pt) length = "约 " + (pt.minutes / 60).toFixed(1) + " 小时";

				// 开发商
				const devs = (r.companies || []).map(c => c.display_name || c.name).filter(Boolean).join("、") || "未知";

				// 标签：只取有名字的，最多 20 个
				const tags = (r.tags || [])
					.map(t => t.display_name || t.name || "")
					.filter(Boolean)
					.filter((v, i, a) => a.indexOf(v) === i)
					.slice(0, 20)
					.map(n => ({ name: n, spoiler: 0, rating: null }));

				return {
					title: cn || orig || "未知作品",
					original: orig || "无原名",
					cover: (r.cover && r.cover.url) || "",
					rating: rating,
					length: length,
					released: r.release_date || "未知",
					languages: [r.olang === "ja" ? "日语" : r.olang].filter(Boolean).join("、") || "未知",
					platforms: "见来源站",
					developers: devs,
					id: r.id || id,
					desc: zhDesc || jaDesc,
					translate: !zhDesc && !!jaDesc,
					tags: tags
				};
			}
		};

		// 生成检索变体：原词 -> 去符号 -> 逐字缩短
		function buildVariants(kw) {
			const raw = String(kw || "").trim();
			if (!raw) return [];
			const out = [raw];
			// 去掉常见修饰符号（＊ ～ ◆ · ・ ★ ☆ - _ 空格）
			const stripped = raw.replace(/[\uFF0A\uFF5E\u301C\u25C6\u30FB\u00B7\u2605\u2606\s\-_\/\\]+/g, "");
			if (stripped && stripped !== raw) out.push(stripped);
			// 逐字缩短：长词只用前缀，覆盖面更广
			if (stripped.length >= 4) out.push(stripped.slice(0, Math.max(2, Math.ceil(stripped.length * 0.6))));
			if (stripped.length >= 3) out.push(stripped.slice(0, 2));
			return out.filter((v, i, a) => v && a.indexOf(v) === i);
		}

		// 从搜索结果里挑最合适的显示名：中文 > 日文 > 原名
		function pickTitle(x) {
			const loc = x.localized || {};
			const order = ["zh-Hans", "zh-Hant", "ja"];
			for (let i = 0; i < order.length; i++) {
				const v = loc[order[i]];
				if (v && v.value) return v.value;
			}
			return x.display_name || "未知";
		}

		function normImg(u) {
			if (!u) return "";
			const t = String(u).trim();
			return t.indexOf("//") === 0 ? "https:" + t : t;
		}

		const ADAPTERS = { vndb: VNDB, nextmoe: NEXTMOE };

		// 完整 VNDB 标签中英文映射表（惰性构建，只建一次）
		function buildTagMap() {
			return {
				"ADV": "文字冒险",
				"VN": "视觉小说",
				"NVL": "纯文字冒险",
				"Kinetic Novel": "线性小说",
				"RPG": "角色扮演",
				"Simulation": "模拟",
				"Strategy": "策略",
				"Action": "动作",
				"Puzzle": "解谜",
				"Rhythm": "节奏",
				"Shooter": "射击",
				"Fantasy": "奇幻",
				"Sci-fi": "科幻",
				"Horror": "恐怖",
				"Mystery": "悬疑",
				"Romance": "恋爱",
				"Comedy": "喜剧",
				"Drama": "戏剧",
				"Slice of Life": "日常",
				"Supernatural": "超自然",
				"Historical": "历史",
				"Sports": "运动",
				"War": "战争",
				"Cyberpunk": "赛博朋克",
				"Steampunk": "蒸汽朋克",
				"Post-apocalyptic": "末日",
				"High School": "高中",
				"College": "大学",
				"Workplace": "职场",
				"Idol": "偶像",
				"Ninja": "忍者",
				"Samurai": "武士",
				"Mecha": "机甲",
				"Vampire": "吸血鬼",
				"Werewolf": "狼人",
				"Magic": "魔法",
				"Mythology": "神话",
				"Space": "太空",
				"Time Travel": "时间旅行",
				"Parallel Worlds": "平行世界",
				"Sexual Content": "成人内容",
				"Nudity": "裸露",
				"Violence": "暴力",
				"Blood": "血腥",
				"Depression": "抑郁",
				"Trauma": "创伤",
				"Male Protagonist": "男性主角",
				"Female Protagonist": "女性主角",
				"Multiple Protagonists": "多主角",
				"Otome": "乙女",
				"BL": "耽美",
				"GL": "百合",
				"Harem": "后宫",
				"Reverse Harem": "逆后宫",
				"Loli": "萝莉",
				"Shota": "正太",
				"Tsundere": "傲娇",
				"Yandere": "病娇",
				"Kuudere": "三无",
				"Dandere": "害羞",
				"Fighting Heroine": "战斗女主角",
				"Sword Combat": "剑术战斗",
				"Imouto-type Heroine": "妹妹型女主角",
				"Oneesan-type Heroine": "姐姐型女主角",
				"Miko Heroine": "巫女型女主角",
				"Kunoichi Heroine": "女忍者型女主角",
				"Sentient Weapon Heroine": "有自我意识的武器型女主角",
				"Fighting Protagonist": "战斗型主角",
				"Slice of Life Comedy": "日常喜剧",
				"Multiple Endings": "多结局",
				"Loli Heroine": "萝莉型女主角",
				"Father Support Character": "父亲型支援角色",
				"Shinto Shrine": "神社",
				"Cousin Romance": "表亲恋爱",
				"Protagonist's Cousin as a Heroine": "主角的表亲作为女主角",
				"Skip Scenes": "场景跳过",
				"Guro": "猎奇",
				"Life and Death Drama": "生死戏剧",
				"One True End": "真结局",
				"Bondage": "束缚",
				"Modern Day Japan": "现代日本",
				"Madness": "疯狂",
				"Confinement": "监禁",
				"High Sexual Content": "高成人内容",
				"Western-style Manor": "西式庄园",
				"Time Loop": "时间循环",
				"Pregnancy": "怀孕",
				"Revenge": "复仇",
				"Apocalypse": "末日",
				"Fear of Death": "死亡恐惧",
				"Maid Heroine": "女仆型女主角",
				"Protagonist with Voice Acting": "主角有配音",
				"Amnesia": "失忆",
				"Graphic Violence": "血腥暴力"
			};
		}

		function getTagMap() {
			return __tagMapCache || (__tagMapCache = buildTagMap());
		}

		// ==================== 数据源切换 ====================
		function switchSource(name, btn) {
			if (!ADAPTERS[name]) return;
			CURRENT.source = name;
			document.querySelectorAll(".source-tab").forEach(b => b.classList.remove("active"));
			if (btn) btn.classList.add("active");
			gameInput.placeholder = name === "vndb"
				? "输入游戏名称，查询 VNDB 数据库..."
				: "输入游戏名称，查询 NextMoe 数据库...";
			detailArea.style.display = "none";
			searchResultArea.style.display = "block";
			searchResultArea.innerHTML = "已切换到 <b>" + SOURCE_LABEL[name] + "</b>，输入游戏名称后点击查询～";
			resetDetailFields();
		}

		function resetDetailFields() {
			["detailLength", "detailReleased", "detailLanguages", "detailPlatforms",
				"detailDevelopers", "detailId", "detailTags", "detailRating"].forEach(id => {
					const el = document.getElementById(id);
					if (el) el.innerHTML = "";
				});
			document.getElementById("detailTitle").textContent = "";
			document.getElementById("detailOriginal").textContent = "";
			const dc = document.getElementById("detailDescription");
			if (dc) { dc.innerHTML = ""; dc.style.display = "none"; }
			gameCover.style.display = "none";
			coverLoading.style.display = "none";
		}

		// ==================== 统一渲染 ====================
		async function getGameDetail(gameId) {
			const startTime = Date.now();
			const src = CURRENT.source;
			detailArea.style.display = "block";
			searchResultArea.style.display = "none";

			coverLoading.style.display = "flex";
			gameCover.style.display = "none";
			document.getElementById("detailTitle").textContent = "";
			document.getElementById("detailOriginal").textContent = "";

			["detailLength", "detailReleased", "detailLanguages", "detailPlatforms",
				"detailDevelopers", "detailId", "detailTags", "detailRating"].forEach(id => {
					const el = document.getElementById(id);
					if (el) el.innerHTML = '<div class="loading-wrapper"><span class="spin-loading"></span></div>';
				});

			const descLoading = document.querySelector("#detailDescription").previousElementSibling;
			const descContent = document.getElementById("detailDescription");
			descLoading.style.display = "flex";
			descContent.style.display = "none";
			descContent.textContent = "";
			document.getElementById("detailSearchTime").textContent = "";

			try {
				const d = await ADAPTERS[src].detail(gameId);

				// 封面
				gameCover.src = d.cover || "https://img.icons8.com/fluency/96/000000/game.png";
				coverLoading.style.display = "none";
				gameCover.style.display = "block";

				document.getElementById("detailTitle").textContent = d.title || "未知";
				document.getElementById("detailOriginal").textContent = d.original || "无原名";
				document.getElementById("detailRating").textContent = d.rating || "暂无评分";
				document.getElementById("detailLength").textContent = d.length || "未知";
				document.getElementById("detailReleased").textContent = d.released || "未知";
				document.getElementById("detailLanguages").textContent = d.languages || "未知";
				document.getElementById("detailPlatforms").textContent = d.platforms || "未知";
				document.getElementById("detailDevelopers").textContent = d.developers || "未知";
				document.getElementById("detailId").textContent = SOURCE_LABEL[src] + " · " + d.id;

				// 标签
				const tagsContainer = document.getElementById("detailTags");
				if (d.tags && d.tags.length) {
					let html = '<div class="tags-container">';
					d.tags.forEach(t => {
						const spo = t.spoiler === 0 ? "（无剧透）" : (t.spoiler === 1 ? "（轻度剧透）" : "（重度剧透）");
						const tipAttr = t.rating == null ? spo : "评分：" + t.rating.toFixed(1) + " " + spo;
						html += '<span class="tag-item" title="' + escapeAttr(tipAttr) + '">' + escapeHtml(t.name) + "</span>";
					});
					html += "</div>";
					tagsContainer.innerHTML = html;
				} else {
					tagsContainer.textContent = "无标签数据";
				}

				// 简介
				let desc = d.desc || "";
				if (d.translate && desc) desc = await translateDescription(desc);
				desc = cleanText(desc);
				descLoading.style.display = "none";
				descContent.style.display = "block";
				descContent.innerHTML = desc ? escapeHtml(desc).replace(/\n/g, "<br>") : "暂无简介";

				const cost = ((Date.now() - startTime) / 1000).toFixed(2);
				document.getElementById("detailSearchTime").textContent =
					"数据源：" + SOURCE_LABEL[src] + " | 耗时：" + cost + "s";
			} catch (error) {
				detailArea.style.display = "none";
				searchResultArea.style.display = "block";
				searchResultArea.innerHTML = '<div class="error">获取详情失败：' + escapeHtml(error.message) + "</div>";
			}
		}

		// ==================== 搜索 ====================
		async function searchGame() {
			const keyword = gameInput.value.trim();
			if (!keyword) { alert("请输入游戏名称哦～"); return; }

			const src = CURRENT.source;
			const startTime = Date.now();
			searchResultArea.style.display = "block";
			searchResultArea.innerHTML = '<div class="loading-wrapper"><span class="spin-loading"></span>' +
				"<span>正在查询 " + SOURCE_LABEL[src] + "...</span></div>";
			detailArea.style.display = "none";
			searchBtn.disabled = true;

			try {
				const r = await ADAPTERS[src].search(keyword);
				if (!r.total) {
					searchResultArea.innerHTML = '<div class="error">未查询到相关游戏，请更换关键词重试～</div>';
					return;
				}

				const badge = src === "nextmoe" ? "NextMoe" : "VNDB";
				let html = '<div class="search-result"><div class="result-count">' +
					SOURCE_LABEL[src] + " 共 " + r.total + ' 条结果<span class="source-badge ' + src + '">' + badge + "</span></div>" +
					'<div class="game-list">';

				r.items.forEach((g, i) => {
					html += '<div class="game-item item-animate" style="animation-delay:' + (i * 0.05) + 's"' +
						' data-id="' + escapeAttr(String(g.id)) + '">' +
						'<div class="game-id">' + escapeHtml(String(g.sub || "")) + "</div>" +
						'<div class="game-name">' + escapeHtml(g.title || "未知") + "</div></div>";
				});
				html += "</div></div>";

				const cost = ((Date.now() - startTime) / 1000).toFixed(2);
				html += '<div class="search-time">耗时 ' + cost + "s | 点击游戏名查看详情</div>";
				searchResultArea.innerHTML = html;

				// 事件委托（避免内联 onclick 拼接注入）
				searchResultArea.querySelectorAll(".game-item").forEach(el => {
					el.addEventListener("click", () => getGameDetail(el.getAttribute("data-id")));
				});
			} catch (error) {
				let tip = "";
				if (src !== "vndb") {
					tip = '<div class="retry-hint">该数据源在浏览器直连时可能被网络或 CORS 拦截。' +
						"可以换成 <b>VNDB</b> 重试，或直接访问来源站查询。</div>";
				}
				searchResultArea.innerHTML = '<div class="error">查询失败：' + escapeHtml(error.message) + "</div>" + tip;
			} finally {
				searchBtn.disabled = false;
			}
		}

		function escapeHtml(s) {
			return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({
				"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", '"': "&#39;"
			}[c]));
		}

		function escapeAttr(s) { return escapeHtml(s); }

		// ==================== 评分排行榜 ====================
		// NextMoe /v2/catalog/rankings 支持：by / metric / order / scope / sources /
		// min_votes / max_votes / score_min / score_max / year / circulation /
		// playtime_min / playtime_max / limit / page 等，这里尽量都暴露出来。
		const RANK_PRESETS = {
			best: { by: "consensus", metric: "lower", label: "综合神作" },
			egs: { by: "erogamescape", metric: "native", min_votes: "50", label: "批评空间中央值" },
			bangumi: { by: "bangumi", metric: "mean", min_votes: "100", label: "Bangumi 均值" },
			vndb: { by: "vndb", metric: "mean", min_votes: "100", label: "VNDB 均值" },
			hltb: { by: "howlongtobeat", metric: "mean", min_votes: "50", label: "HowLongToBeat" },
			hidden: { by: "consensus", metric: "mean", min_votes: "5", max_votes: "50", label: "冷门佳作（5-50 票）" },
			divergence: { by: "consensus", metric: "divergence", label: "各站分歧最大" },
			landmine: { by: "consensus", metric: "upper", order: "asc", min_votes: "50", label: "地雷榜" }
		};

		const RANK_STATE = {
			preset: "best",
			nsfw: true,
			year: "",
			minVotes: "",
			cursor: null,
			items: [],
			loading: false
		};

		function pickPreset(key, btn) {
			if (!RANK_PRESETS[key]) return;
			RANK_STATE.preset = key;
			document.querySelectorAll(".rp-chip").forEach(b => b.classList.remove("active"));
			if (btn) btn.classList.add("active");
			// 「冷门佳作」自带票数区间，与手动票数筛选冲突，切换时清空后者
			if (key === "hidden") {
				RANK_STATE.minVotes = "";
				const vs = document.getElementById("votesSelect");
				if (vs) vs.value = "";
			}
			reloadRanking();
		}

		function toggleNsfw(btn) {
			RANK_STATE.nsfw = !RANK_STATE.nsfw;
			if (btn) btn.classList.toggle("active", RANK_STATE.nsfw);
			reloadRanking();
		}

		// ---------- 年份区间选择（动态生成，永远跟上当年） ----------
		const YEAR_MIN = 1980;              // 早期 PC-98 / 老 galgame 也覆盖到
		let yearPicked = [];                 // 已点选的年份（0~2 个）

		function currentYear() {
			return new Date().getFullYear();
		}

		function buildYearGrid() {
			const grid = document.getElementById("ypGrid");
			if (!grid) return;
			const top = currentYear();
			let html = "";
			// 从最新往回排，方便先看到近期年份
			for (let y = top; y >= YEAR_MIN; y--) {
				html += '<button type="button" class="yp-year" data-year="' + y + '" onclick="pickYear(' + y + ')">' + y + "</button>";
			}
			grid.innerHTML = html;
			syncYearGrid();
		}

		function syncYearGrid() {
			const grid = document.getElementById("ypGrid");
			if (!grid) return;
			const top = currentYear();
			const a = yearPicked.length ? Math.min.apply(null, yearPicked) : null;
			const b = yearPicked.length ? Math.max.apply(null, yearPicked) : null;
			grid.querySelectorAll(".yp-year").forEach(btn => {
				const y = parseInt(btn.getAttribute("data-year"), 10);
				btn.classList.remove("single", "range", "edge");
				if (y === top) btn.classList.add("is-current");
				if (a === null) return;
				if (yearPicked.length === 1 && y === a) btn.classList.add("single");
				else if (y >= a && y <= b) {
					btn.classList.add("range");
					if (y === a || y === b) btn.classList.add("edge");
				}
			});
			const hint = document.getElementById("ypHint");
			if (hint) {
				if (!yearPicked.length) hint.textContent = "点一个年份选单年，再点第二个可组成区间";
				else if (yearPicked.length === 1) hint.textContent = "单年榜：" + a + "（再点一个年份可扩展为区间）";
				else hint.textContent = "区间：" + a + " — " + b + "（共 " + (b - a + 1) + " 年）";
			}
		}

		function pickYear(y) {
			if (yearPicked.length === 1 && yearPicked[0] === y) {
				// 再点自己 = 取消该年份
				yearPicked = [];
			} else if (yearPicked.length === 0) {
				yearPicked = [y];                     // 第一次点：单选该年，立即生效
			} else if (yearPicked.length === 1) {
				yearPicked.push(y);                   // 第二次点：扩展为区间
			} else {
				yearPicked = [y];                     // 已满两个，重新从单选开始
			}
			syncYearGrid();
			applyYear();                             // ★ 每次点击都立即应用到榜单
		}

		function quickYear(n) {
			yearPicked = [];
			if (!n) { applyYear(); return; }
			const top = currentYear();
			// n===1 表示「今年」单年，其余为「近 N 年」闭区间
			yearPicked = (n === 1) ? [top] : [top - n + 1, top];
			syncYearGrid();
			applyYear();
		}

		function clearYear() {
			yearPicked = [];
			syncYearGrid();
			applyYear();
		}

		function applyYear() {
			if (!yearPicked.length) {
				RANK_STATE.year = "";
			} else {
				const a = Math.min.apply(null, yearPicked);
				const b = Math.max.apply(null, yearPicked);
				// 单一年份传 year=2020；区间传 year=2015-2020（官方支持的闭区间写法）
				RANK_STATE.year = (a === b) ? String(a) : (a + "-" + b);
			}
			const btn = document.getElementById("yearBtn");
			if (btn) {
				btn.textContent = RANK_STATE.year ? ("年份 " + RANK_STATE.year + " ▾") : "全部年份 ▾";
				btn.classList.toggle("active", !!RANK_STATE.year);
			}
			reloadRanking();
		}

		function openYearPicker() {
			const el = document.getElementById("yearPicker");
			if (!el) return;
			const willOpen = !el.classList.contains("active");
			el.classList.toggle("active", willOpen);
			if (willOpen) {
				buildYearGrid();
				// 滚到当前选中年份附近
				const target = yearPicked.length ? null : document.querySelector(".yp-year.is-current");
				if (target) target.scrollIntoView({ block: "center" });
			}
		}

		function changeMinVotes(sel) {
			RANK_STATE.minVotes = sel ? sel.value : "";
			reloadRanking();
		}

		function buildRankQuery() {
			const p = RANK_PRESETS[RANK_STATE.preset] || RANK_PRESETS.best;
			const parts = ["limit=30"];
			parts.push("nsfw=" + (RANK_STATE.nsfw ? "true" : "false"));
			if (p.by) parts.push("by=" + p.by);
			if (p.metric) parts.push("metric=" + p.metric);
			if (p.order) parts.push("order=" + p.order);
			if (p.min_votes) parts.push("min_votes=" + p.min_votes);
			if (p.max_votes) parts.push("max_votes=" + p.max_votes);
			if (RANK_STATE.minVotes) parts.push("min_votes=" + RANK_STATE.minVotes);
			if (RANK_STATE.year) parts.push("year=" + encodeURIComponent(RANK_STATE.year));
			if (RANK_STATE.cursor) parts.push("cursor=" + encodeURIComponent(RANK_STATE.cursor));
			return "/catalog/rankings?" + parts.join("&");
		}

		function reloadRanking() {
			RANK_STATE.cursor = null;
			RANK_STATE.items = [];
			const list = document.getElementById("rankingList");
			if (list) list.innerHTML = '<div class="loading-wrapper"><span class="spin-loading"></span><span>正在加载排行榜...</span></div>';
			const more = document.getElementById("rankingMore");
			if (more) more.style.display = "none";
			fetchRanking(false);
		}

		function loadMoreRanking() {
			if (RANK_STATE.loading || !RANK_STATE.cursor) return;
			fetchRanking(true);
		}

		async function fetchRanking(append) {
			if (RANK_STATE.loading) return;
			RANK_STATE.loading = true;
			const moreBtn = document.getElementById("rankingMoreBtn");
			if (moreBtn) { moreBtn.disabled = true; moreBtn.textContent = "加载中..."; }

			try {
				const r = await NEXTMOE.get(buildRankQuery());
				const items = r.items || [];
				RANK_STATE.cursor = r.next_cursor || null;

				const unitMap = { deviation: "偏差值", native: "来源原分", percentile: "百分位", votes: "票数" };
				const unitEl = document.getElementById("rankingUnit");
				if (unitEl && !append) {
					unitEl.textContent = (unitMap[r.unit] || r.unit || "") + (r.model ? " · 模型v" + (r.model.id || "") : "");
				}

				if (!items.length && !append) {
					const list = document.getElementById("rankingList");
					list.innerHTML = '<div class="error">该筛选条件下暂无数据，换个条件试试～</div>';
					return;
				}

				RANK_STATE.items = append ? RANK_STATE.items.concat(items) : items;
				renderRanking(append ? items : null);

				const more = document.getElementById("rankingMore");
				if (more) more.style.display = RANK_STATE.cursor ? "block" : "none";
			} catch (e) {
				const list = document.getElementById("rankingList");
				if (list && !append) {
					list.innerHTML = '<div class="error">排行榜加载失败：' + escapeHtml(e.message) + "</div>" +
						'<div class="retry-hint">接口直连可能被网络或 CORS 拦截。' +
						'可以<a href="https://www.kungal.com/ranking" target="_blank" rel="noopener">直接访问排行榜</a>。</div>';
				}
			} finally {
				RANK_STATE.loading = false;
				if (moreBtn) { moreBtn.disabled = false; moreBtn.textContent = "加载更多"; }
			}
		}

		function rankItemHtml(it) {
			const w = it.work || {};
			const cs = it.consensus || {};
			const name = pickTitle(w);
			const cover = (w.cover && w.cover.url) || "";
			const score = cs.score != null ? cs.score.toFixed(1) : (it.value != null ? it.value.toFixed(1) : "-");
			const srcs = cs.source_count || 0;
			const votes = cs.vote_count || 0;
			const link = "https://www.kungal.com/galgame/" + encodeURIComponent(w.id || "");

			// 各站原分（最多展示 3 个，避免拥挤）
			const perf = (it.per_source || []).slice(0, 3).map(x => x.source + " " + x.score).join(" · ");

			return '<a class="rank-item" href="' + link + '" target="_blank" rel="noopener noreferrer">' +
				'<div class="rank-no">' + it.rank + "</div>" +
				(cover ? '<img class="rank-cover" loading="lazy" src="' + escapeAttr(cover) + '" alt="">' : '<div class="rank-cover"></div>') +
				'<div class="rank-info"><div class="rank-name">' + escapeHtml(name) + "</div>" +
				'<div class="rank-meta">' + escapeHtml(w.release_date || "") +
				(srcs ? " · " + srcs + " 源" : "") + (votes ? " · " + votes + " 票" : "") + "</div>" +
				(perf ? '<div class="rank-meta rank-perf">' + escapeHtml(perf) + "</div>" : "") +
				"</div>" +
				'<div class="rank-score"><div class="rank-score-val">' + score + "</div>" +
				'<div class="rank-score-sub">共识分</div></div></a>';
		}

		function renderRanking(newItems) {
			const list = document.getElementById("rankingList");
			if (!list) return;
			if (newItems) {
				// 追加模式：只插入新行，保留已有 DOM（避免整表重绘闪烁）
				let html = "";
				newItems.forEach(it => { html += rankItemHtml(it); });
				list.insertAdjacentHTML("beforeend", html);
			} else {
				let html = "";
				RANK_STATE.items.forEach(it => { html += rankItemHtml(it); });
				list.innerHTML = html;
			}
		}

		function openRanking() {
			const el = document.getElementById("rankingPanel");
			if (!el) return;
			el.classList.add("active");
			document.body.style.overflow = "hidden";
			if (!RANK_STATE.items.length && !RANK_STATE.loading) reloadRanking();
		}

		function closeRanking(event) {
			if (event && event.target && event.target.id !== "rankingPanel") return;
			const el = document.getElementById("rankingPanel");
			if (el) el.classList.remove("active");
			document.body.style.overflow = "auto";
		}

		// ESC 关闭排行榜
		document.addEventListener("keydown", function (e) {
			if (e.key === "Escape") { closeRanking(); }
		});

		// 回车键查询
		gameInput.addEventListener("keypress", function(e) {
			if (e.key === "Enter") {
				searchGame();
			}
		});
		
// 伸缩文字播放器逻辑
// 优化音乐播放器逻辑
const playerContainer = document.getElementById('playerContainer');
const playerIcon = document.getElementById('playerIcon');
const playerText = document.getElementById('playerText');
const floatPlayer = document.getElementById('floatPlayer');
let isPlaying = false; // 播放状态
let isExpanded = false; // 展开状态

// 初始化播放器文本（添加标题高亮）
playerText.innerHTML = '<span class="player-title">▶️ 正在播放：</span>二人のクロニクル';

// 点击容器：切换播放/暂停 + 展开/收缩
playerContainer.addEventListener('click', () => {
  if (!isPlaying) {
	// 未播放：播放 + 展开 + 播放状态样式
	floatPlayer.play();
	isPlaying = true;
	playerIcon.textContent = '⏸️';
	playerContainer.classList.add('active', 'playing');
	isExpanded = true;
	playerText.innerHTML = '<span class="player-title">⏸️ 正在播放：</span>二人のクロニクル';
  } else {
	// 已播放：暂停 + 收缩 + 暂停状态样式
	floatPlayer.pause();
	isPlaying = false;
	playerIcon.textContent = '▶️';
	playerContainer.classList.remove('active', 'playing');
	isExpanded = false;
	playerText.innerHTML = '<span class="player-title">▶️ 正在播放：</span>二人のクロニクル';
  }
});

// 点击页面其他区域：自动收缩
document.addEventListener('click', (e) => {
  if (isExpanded && !playerContainer.contains(e.target)) {
	playerContainer.classList.remove('active');
	isExpanded = false;
  }
});

// 音乐播放结束：自动切换为暂停状态
floatPlayer.addEventListener('ended', () => {
  isPlaying = false;
  playerIcon.textContent = '▶️';
  playerContainer.classList.remove('playing');
  playerText.innerHTML = '<span class="player-title">▶️ 播放结束：</span>二人のクロニクル';
});

// AI聊天板块：浏览器不支持 WebM/VP9 时，回退到原始 GIF 背景（兼容性保底）
(function () {
	var panel = document.querySelector('.ai-chat-panel');
	var video = document.querySelector('.ai-chat-panel-bg');
	if (!panel || !video) return;
	var probe = document.createElement('video');
	if (!probe.canPlayType('video/webm; codecs="vp9"') && !probe.canPlayType('video/webm')) {
		panel.style.backgroundImage = "url('image/ttk.gif')";
		panel.style.backgroundSize = '100% auto';
		panel.style.backgroundRepeat = 'no-repeat';
		video.parentNode.removeChild(video);
	}
})();
