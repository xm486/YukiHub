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
			searchResultArea.innerHTML = "输入游戏名称，点击查询获取vndb数据库结果～";
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

		// 查询游戏详情 - 修复旧数据残留问题
		async function getGameDetail(gameId) {
			const startTime = Date.now();
			detailArea.style.display = "block";
			searchResultArea.style.display = "none";
			
			// 重置所有字段为加载状态
			coverLoading.style.display = "flex";
			gameCover.style.display = "none";
			
			// 重置标题和原名
			document.getElementById("detailTitle").textContent = "";
			document.getElementById("detailOriginal").textContent = "";
			
			// 重置所有数据字段为加载状态
			const detailFields = [
				'detailLength',
				'detailReleased',
				'detailLanguages',
				'detailPlatforms',
				'detailDevelopers',
				'detailId',
				'detailTags',
				'detailRating'
			];
			
			detailFields.forEach(fieldId => {
				const element = document.getElementById(fieldId);
				if (element) {
					element.innerHTML = '<div class="loading-wrapper"><span class="spin-loading"></span></div>';
				}
			});
			
			// 重置游戏简介
			const descLoading = document.querySelector("#detailDescription").previousElementSibling;
			const descContent = document.getElementById("detailDescription");
			descLoading.style.display = "flex";
			descContent.style.display = "none";
			descContent.textContent = "";
			
			// 重置搜索时间
			document.getElementById("detailSearchTime").textContent = "";

			try {
				const res = await fetch("https://api.vndb.org/kana/vn", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({
						filters: ["id", "=", gameId],
						fields: "title,image.url,alttitle,released,languages,platforms,description,length,length_minutes,developers.id,developers.name,tags{id,name,spoiler,rating},rating,votecount"
					})
				});

				if (!res.ok) throw new Error("API请求失败");
				const data = await res.json();
				const game = data.results[0];
				if (!game) throw new Error("未找到该游戏详情");

				const translatedDesc = await translateDescription(game.description);

				// 处理时长
				const lengthText = LENGTH_MAP[game.length] || "未知";
				const hours = (game.length_minutes / 60).toFixed(1);
				const lengthStr = `${lengthText}（${hours}小时）`;

				// 处理开发商
				const developers = game.developers.map(dev => dev.name).join("、");
				
				// 新增：标签中英文映射表（可根据需求扩展）
// 新增：完整VNDB标签中英文映射表
const TAG_TRANSLATE_MAP = (__tagMapCache || (__tagMapCache = {
  // 类型类
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
  
  // 题材类
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
  
  // 设定类
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
  
  // 内容类
  "Sexual Content": "成人内容",
  "Nudity": "裸露",
  "Violence": "暴力",
  "Blood": "血腥",
  "Depression": "抑郁",
  "Trauma": "创伤",
  
  // 角色类
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
}));

// 处理游戏标签（替换原有逻辑）
const tagsContainer = document.getElementById("detailTags");
if (game.tags && game.tags.length > 0) {
  let tagsHtml = '<div class="tags-container">';
  game.tags.forEach(tag => {
	// 标签翻译：优先用映射表，无则保留英文
	const tagName = TAG_TRANSLATE_MAP[tag.name] || tag.name;
	// 标签 spoiler 等级说明：0=无剧透，1=轻度，2=重度
	const spoilerText = tag.spoiler === 0 ? '（无剧透）' : tag.spoiler === 1 ? '（轻度剧透）' : '（重度剧透）';
	const ratingText = typeof tag.rating === "number" ? tag.rating.toFixed(1) : "暂无";
	tagsHtml += `<span class="tag-item" title="评分：${ratingText} ${spoilerText}">${tagName}</span>`;
  });
  tagsHtml += '</div><div class="tag-note">标签评分：0-3分（越高越贴合）</div>';
  tagsContainer.innerHTML = tagsHtml;
} else {
  tagsContainer.textContent = "无标签数据";
}

// 处理评分
document.getElementById("detailRating").textContent = game.rating 
  ? `${(game.rating / 10).toFixed(1)}分（${game.votecount}人评分）` 
  : "无评分数据";

				// 填充数据
				gameCover.src = game.image?.url || "https://img.icons8.com/fluency/96/000000/game.png";
				coverLoading.style.display = "none";
				gameCover.style.display = "block";
				document.getElementById("detailTitle").textContent = game.title;
				document.getElementById("detailOriginal").textContent = game.alttitle || "无原名";
				document.getElementById("detailLength").textContent = lengthStr;
				document.getElementById("detailReleased").textContent = game.released || "未知";
				document.getElementById("detailLanguages").textContent = game.languages.join("、") || "未知";
				document.getElementById("detailPlatforms").textContent = game.platforms.join("、") || "未知";
				document.getElementById("detailDevelopers").textContent = developers || "未知";
				document.getElementById("detailId").textContent = game.id;
				descLoading.style.display = "none";
				descContent.style.display = "block";
				
				// 直接设置文本内容，不使用pre-line或pre-wrap
				// 使用innerHTML来处理换行
				const formattedDesc = translatedDesc.replace(/\n/g, '<br>');
				descContent.innerHTML = formattedDesc;
				
				// 更新描述容器的CSS
				const descriptionContainer = document.getElementById("descriptionContainer");
				descriptionContainer.style.whiteSpace = "normal"; // 使用正常换行
				descriptionContainer.style.wordBreak = "break-word"; // 允许单词内断行

				// 搜索耗时
				const costTime = ((Date.now() - startTime) / 1000).toFixed(2);
				document.getElementById("detailSearchTime").textContent = `搜索耗时：${costTime}s`;
			} catch (error) {
				detailArea.style.display = "none";
				searchResultArea.style.display = "block";
				searchResultArea.innerHTML = `<div class="error">获取详情失败：${error.message}</div>`;
			}
		}

		// 搜索游戏 - 修复列表渲染
		async function searchGame() {
			const gameName = gameInput.value.trim();
			if (!gameName) {
				alert("请输入游戏名称哦～");
				return;
			}

			const startTime = Date.now();
			searchResultArea.style.display = "block";
			searchResultArea.innerHTML = `
				<div class="loading-wrapper">
					<span class="spin-loading"></span>
					<span>正在查询vndb数据库...</span>
				</div>
			`;
			detailArea.style.display = "none";
			searchBtn.disabled = true;

			try {
				const res = await fetch("https://api.vndb.org/kana/vn", {
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({
						filters: ["search", "=", gameName],
						fields: "title,image.url,alttitle,released",
						count: true,
						results: 30
					})
				});

				if (!res.ok) throw new Error("API请求失败");
				const data = await res.json();
				const totalCount = data.count;
				const games = data.results;

				if (totalCount === 0) {
					searchResultArea.innerHTML = '<div class="error">未查询到相关游戏，请更换关键词重试～</div>';
					return;
				}

				let gameListHtml = `
					<div class="search-result">
						<div class="result-count">总结果数：${totalCount}（显示前30条）</div>
						<div class="game-list">
					`;
				games.forEach((game, index) => {
					// 改为通过添加类来控制动画，避免默认样式隐藏内容
					const delay = `${index * 0.05}s`;
					gameListHtml += `
						<div class="game-item item-animate" style="animation-delay: ${delay};" onclick="getGameDetail('${game.id}')">
							<div class="game-id">ID：${game.id}</div>
							<div class="game-name">${game.title}</div>
						</div>
					`;
				});
				gameListHtml += `</div></div>`;

				const costTime = ((Date.now() - startTime) / 1000).toFixed(2);
				gameListHtml += `<div class="search-time">搜索耗时：${costTime}s | 点击游戏名称查看详情</div>`;

				searchResultArea.innerHTML = gameListHtml;
			} catch (error) {
				searchResultArea.innerHTML = `<div class="error">查询失败：${error.message}</div>`;
			} finally {
				searchBtn.disabled = false;
			}
		}

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
