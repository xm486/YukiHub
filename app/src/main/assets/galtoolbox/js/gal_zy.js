// 平滑滚动到对应区域
document.addEventListener('DOMContentLoaded', function() {
	const jumpButtons = document.querySelectorAll('.jump-btn');
	
	jumpButtons.forEach(button => {
		button.addEventListener('click', function(e) {
			e.preventDefault();
			
			const targetId = this.getAttribute('href');
			const targetElement = document.querySelector(targetId);
			
			if (targetElement) {
				// 使用scrollIntoView方法，确保精准跳转
				targetElement.scrollIntoView({
					behavior: 'smooth',
					block: 'start'
				});
				
				// 添加高亮效果
				const originalBg = targetElement.style.backgroundColor;
				targetElement.style.backgroundColor = 'rgba(74, 110, 224, 0.05)';
				targetElement.style.transition = 'background-color 0.5s';
				
				setTimeout(() => {
					targetElement.style.backgroundColor = originalBg;
				}, 1000);
			}
		});
	});
	
	// 添加页面加载时的动画
	const linkCards = document.querySelectorAll('.link-card');
	linkCards.forEach((card, index) => {
		card.style.opacity = '0';
		card.style.transform = 'translateY(20px)';
		
		setTimeout(() => {
			card.style.transition = 'opacity 0.5s ease, transform 0.5s ease';
			card.style.opacity = '1';
			card.style.transform = 'translateY(0)';
		}, 100 + index * 30);
	});
	
	// 新增功能：使用说明模态框
	const helpBtn = document.getElementById('helpBtn');
	const helpModal = document.getElementById('helpModal');
	const closeModal = document.getElementById('closeModal');
	
	helpBtn.addEventListener('click', function() {
		helpModal.classList.add('active');
	});
	
	closeModal.addEventListener('click', function() {
		helpModal.classList.remove('active');
	});
	
	// 点击模态框外部关闭
	helpModal.addEventListener('click', function(e) {
		if (e.target === helpModal) {
			helpModal.classList.remove('active');
		}
	});
	
	// 新增功能：返回顶部按钮
	const backToTop = document.getElementById('backToTop');
	
	// 监听滚动事件，显示/隐藏返回顶部按钮
	window.addEventListener('scroll', function() {
		if (window.pageYOffset > 300) {
			backToTop.classList.add('active');
		} else {
			backToTop.classList.remove('active');
		}
	});
	
	// 返回顶部功能
	backToTop.addEventListener('click', function() {
		window.scrollTo({
			top: 0,
			behavior: 'smooth'
		});
	});
});