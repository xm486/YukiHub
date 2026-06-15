var TyranoPlayer = (function() {
    var COUNTRY = 'aaaaa';
    var TyranoPlayer = function(storage_url) {
        if (!(this instanceof TyranoPlayer)) {
            return new TyranoPlayer(storage_url);
        }
        this.storage_url = storage_url;
    }
    var p = TyranoPlayer.prototype;
    p.pauseAllAudio = function() {
        console.log("pause All Audio!");
        console.log(TYRANO.kag.tmp.map_bgm);
        var bgm_objs = TYRANO.kag.tmp.map_bgm;
        var se_objs = TYRANO.kag.tmp.map_se;
        for (var key in bgm_objs) { bgm_objs[key].pause(); }
        for (var key in se_objs) { se_objs[key].pause(); }
    }
    p.resumeAllAudio = function() {
        console.log("resume All Audio!");
        var bgm_objs = TYRANO.kag.tmp.map_bgm;
        var se_objs = TYRANO.kag.tmp.map_se;
        if (bgm_objs[TYRANO.kag.stat.current_bgm]) {
            bgm_objs[TYRANO.kag.stat.current_bgm].play();
        } else if (bgm_objs[0]) {
            bgm_objs[0].play();
        }
    }
    return TyranoPlayer;
})();
var _tyrano_player = new TyranoPlayer("");
tyrano.base.fitBaseSize = function(width, height) {
    $(".tyrano_base").css("position","absolute");
    var that = this;
    var view_width = $.getViewPort().width;
    var view_height = $.getViewPort().height;
    var width_f = view_width / width;
    var height_f = view_height / height;
    var scale_f = 0;
    var screen_ratio = this.tyrano.kag.config.ScreenRatio;
    if (screen_ratio == "fix") {
        if (width_f > height_f) { scale_f = height_f; }
        else { scale_f = width_f; }
        this.tyrano.kag.tmp.base_scale = scale_f;
        setTimeout(function() {
            $(".tyrano_base").css("transform-origin", "0 0");
            $(".tyrano_base").css({ margin: 0 });
            var w = Math.abs(parseInt(window.innerWidth) - parseInt(that.tyrano.kag.config.scWidth * scale_f)) / 2;
            var h = Math.abs(parseInt(window.innerHeight) - parseInt(that.tyrano.kag.config.scHeight * scale_f)) / 2;
            if (width_f > height_f) { $(".tyrano_base").css("left", w + "px"); $(".tyrano_base").css("top", "0px"); }
            else { $(".tyrano_base").css("left", "0px"); $(".tyrano_base").css("top", h + "px"); }
            $(".tyrano_base").css("transform", "scale(" + scale_f + ")");
            if (parseInt(view_width) < parseInt(w)) { if (scale_f < 1) { window.scrollTo(w, h); } }
        }, 100);
    } else if (screen_ratio == "fit") {
        setTimeout(function() {
            $(".tyrano_base").css("transform", "scaleX(" + width_f + ") scaleY(" + height_f + ")");
            window.scrollTo(width, height);
        }, 100);
    }
};
$.setStorage = function(key, val, type) {
    if ("appJsInterface" in window) {
        appJsInterface.setStorage(key, escape(JSON.stringify(val)));
    } else {
        window.tyrano_save[key] = encodeURIComponent(JSON.stringify(val));
        location.href = 'tyranoplayer-save://?key=' + key + '&data=' + encodeURIComponent(JSON.stringify(val));
    }
}
$.getStorage = function(key, type) {
    console.log("bbbb"); console.log(key);
    if ("appJsInterface" in window) {
        try {
            var json_str = appJsInterface.getStorage(key);
            if (json_str == "") return null;
            return unescape(json_str);
        } catch(e) { console.log(e); }
    } else {
        if (!window.tyrano_save[key] || window.tyrano_save[key] == "") return null;
        return decodeURIComponent(window.tyrano_save[key]);
    }
}
$.openWebFromApp = function(url) {
    if ("appJsInterface" in window) { appJsInterface.openUrl(url); }
    else { location.href = 'tyranoplayer-web://?url=' + url; }
}

setTimeout(function(){
(function(){
var player_back_cnt = 0;

function loadCfg(k, d) {
    try { var r = $.getStorage(k); if (r == null || r === '') return d; return JSON.parse(r); }
    catch(e) { return d; }
}
function saveCfg(k, v) { try { $.setStorage(k, JSON.stringify(v)); } catch(e) {} }

var mo = loadCfg('_yh_menu_opa', 0.68);
if (typeof mo !== 'number' || isNaN(mo) || mo < 0.05 || mo > 1) mo = 0.68;
var ms = loadCfg('_yh_menu_scale', 1.0);
if (typeof ms !== 'number' || isNaN(ms) || ms < 0.4 || ms > 3) ms = 1.0;
var ml = loadCfg('_yh_menu_left', 14);
if (typeof ml !== 'number' || isNaN(ml) || ml < -500 || ml > 10000) ml = 14;
var mt = loadCfg('_yh_menu_top', 14);
if (typeof mt !== 'number' || isNaN(mt) || mt < -500 || mt > 10000) mt = 14;

function saveAll() { saveCfg('_yh_menu_opa', mo); saveCfg('_yh_menu_scale', ms); saveCfg('_yh_menu_left', ml); saveCfg('_yh_menu_top', mt); }

function fz() { return Math.max(8, Math.round(14 * ms)); }
function bsz() { return Math.max(30, Math.round(54 * ms)); }

// 容器（拖动在此层）
var ct = $("<div id='player_menu_container' style='position:absolute;z-index:999999;left:" + ml + "px;top:" + mt + "px;'></div>");

// 收起态：圆按钮
var jmb = $("<div class='player_menu_area' id='player_menu_button' style='display:none;opacity:" + mo + ";text-align:center;border-radius:50%;cursor:pointer;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'></div>");
var jmbSpan = $("<span style='color:white;font-weight:bold;'>菜单</span>");
jmb.append(jmbSpan);

// 展开面板 flex 列布局
var jex = $("<div class='player_menu_area' id='player_menu_expanded' style='display:none;opacity:" + mo + ";'></div>");

function mkBtn(t, bg) {
    var d = $("<div style='padding:8px 12px;border-radius:999px;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,0.35);margin-bottom:6px;white-space:nowrap;text-align:center;background-color:" + (bg || '#007AFF') + ";'></div>");
    d.append($("<span style='color:white;font-weight:bold;'>" + t + "</span>"));
    return d;
}

var jb = mkBtn('关闭菜单'); var je = mkBtn('回到标题');
var ja = mkBtn('自动'); var js = mkBtn('快进');
var jsv = mkBtn('存档'); var jl = mkBtn('读档');
var jc = mkBtn('结束', '#0A84FF');

// 透明度行
var oRow = $("<div style='padding:5px 8px;border-radius:12px;background-color:rgba(0,0,0,0.45);margin-bottom:6px;white-space:nowrap;text-align:center;'></div>");
var oLbl = $("<span style='color:white;vertical-align:middle;margin-right:4px;'>透明</span>");
var oSli = $("<input type='range' min='5' max='100' value='" + Math.round(mo * 100) + "' style='vertical-align:middle;accent-color:#007AFF;'>");
var oVal = $("<span style='color:#ccc;vertical-align:middle;margin-left:4px;'>" + Math.round(mo * 100) + "%</span>");
oRow.append(oLbl, oSli, oVal);

// 大小行
var sRow = $("<div style='padding:5px 8px;border-radius:12px;background-color:rgba(0,0,0,0.45);margin-bottom:6px;white-space:nowrap;text-align:center;'></div>");
var sLbl = $("<span style='color:white;vertical-align:middle;margin-right:4px;'>大小</span>");
var sSli = $("<input type='range' min='40' max='300' value='" + Math.round(ms * 100) + "' style='vertical-align:middle;accent-color:#007AFF;'>");
var sVal = $("<span style='color:#ccc;vertical-align:middle;margin-left:4px;'>" + Math.round(ms * 100) + "%</span>");
sRow.append(sLbl, sSli, sVal);

jex.append(jb, je, ja, js, jsv, jl, jc, oRow, sRow);

function refrOpa() { $(".player_menu_area").css({ opacity: mo }); }
function refrScl() {
    jmbSpan.css({ fontSize: fz() + 'px' });
    jmb.css({ width: bsz() + 'px', height: bsz() + 'px', lineHeight: bsz() + 'px' });
    jex.find('span').css({ fontSize: fz() + 'px' });
    var sw = Math.max(60, Math.round(100 * ms));
    oSli.css({ width: sw + 'px', height: '20px' });
    sSli.css({ width: sw + 'px', height: '20px' });
    oLbl.css({ fontSize: Math.round(12 * ms) + 'px' });
    sLbl.css({ fontSize: Math.round(12 * ms) + 'px' });
    oVal.css({ fontSize: Math.round(11 * ms) + 'px' });
    sVal.css({ fontSize: Math.round(11 * ms) + 'px' });
}
refrScl();

// === 拖动：5px 阈值 ===
var di = null, ds = false, DT = 5;
function clearDrag() { if (di && ds) saveAll(); di = null; ds = false; }
ct.on('touchstart', function(e) {
    clearDrag();
    var t = e.originalEvent.touches[0];
    di = { sx: t.clientX, sy: t.clientY, ox: ml, oy: mt };
    ds = false;
});
$(document).on('touchmove', function(e) {
    if (!di) return;
    var t = e.originalEvent.touches[0];
    var dx = t.clientX - di.sx, dy = t.clientY - di.sy;
    if (!ds && Math.abs(dx) < DT && Math.abs(dy) < DT) return;
    ds = true;
    ml = di.ox + dx; mt = di.oy + dy;
    ct.css({ left: ml + 'px', top: mt + 'px' });
});
ct.on('touchend touchcancel', clearDrag);
$(document).on('touchend touchcancel', clearDrag);

// === 状态切换 ===
function mS() { jmb.show(); jex.hide(); }
function hM() { jex.show(); jmb.hide(); }
function hA() { jmb.hide(); jex.hide(); }

jmb.on('click', function(e) { if (ds) return; hM(); e.stopPropagation(); });
jb.on('click', function(e) { if (ds) return; mS(); e.stopPropagation(); });
je.on('click', function(e) { if (ds) return; mS(); if ("appJsInterface" in window) appJsInterface.finishGame(); else location.href = "tyranoplayer-back://endgame"; e.stopPropagation(); });
jsv.on('click', function(e) { if (ds) return; mS(); TYRANO.kag.menu.displaySave(); e.stopPropagation(); });
jl.on('click', function(e) { if (ds) return; mS(); TYRANO.kag.menu.displayLoad(); e.stopPropagation(); });
ja.on('click', function(e) { if (ds) return; mS(); TYRANO.kag.ftag.startTag("autostart", {}); e.stopPropagation(); });
js.on('click', function(e) { if (ds) return; mS(); TYRANO.kag.ftag.startTag("skipstart", {}); e.stopPropagation(); });
jc.on('click', function(e) { if (ds) return; if ("appJsInterface" in window) appJsInterface.closeGame(); else location.href = "tyranoplayer-back://endgame"; e.stopPropagation(); });

oSli.on('input change', function() { mo = parseInt(this.value) / 100; oVal.text(Math.round(mo * 100) + '%'); saveAll(); refrOpa(); });
sSli.on('input change', function() { ms = parseInt(this.value) / 100; sVal.text(Math.round(ms * 100) + '%'); saveAll(); refrScl(); });
oRow.on('click touchstart', function(e) { e.stopPropagation(); });
sRow.on('click touchstart', function(e) { e.stopPropagation(); });

ct.append(jmb); ct.append(jex); $("body").append(ct);
mS();

$("#tyrano_base").on("click.player", function() { hA(); player_back_cnt = 0; });

// 每 700ms 计数，5 次 = 3.5 秒
setInterval(function() { if (player_back_cnt == 5) mS(); player_back_cnt++; }, 700);

})();
}, 1000);
$.userenv = function() { return 'pc'; }