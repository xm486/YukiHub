
Graphics._stretchHeight = function() {
    if (Utils.isMobileDevice()) {
        return document.documentElement.clientHeight;
    } else {
        return window.innerHeight;
    }
};
