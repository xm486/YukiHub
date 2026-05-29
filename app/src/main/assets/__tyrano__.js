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
        for (var key in bgm_objs) {
            bgm_objs[key].pause();
        }
        for (var key in se_objs) {
            se_objs[key].pause();
        }
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
    var space_width = 0;
    var screen_ratio = this.tyrano.kag.config.ScreenRatio;
    if (screen_ratio == "fix") {
        if (width_f > height_f) {
            scale_f = height_f;
        } else {
            scale_f = width_f;
        }
        this.tyrano.kag.tmp.base_scale = scale_f;
        setTimeout(function() {
            if (true) {
                $(".tyrano_base").css("transform-origin", "0 0");
                $(".tyrano_base").css({
                    margin : 0
                });
                var width = Math.abs(parseInt(window.innerWidth) - parseInt(that.tyrano.kag.config.scWidth * scale_f)) / 2;
                var height = Math.abs(parseInt(window.innerHeight) - parseInt(that.tyrano.kag.config.scHeight * scale_f)) / 2;
                if (width_f > height_f) {
                    $(".tyrano_base").css("left", width + "px");
                    $(".tyrano_base").css("top", "0px");
                } else {
                    $(".tyrano_base").css("left", "0px");
                    $(".tyrano_base").css("top", height + "px");
                }
            }
            $(".tyrano_base").css("transform", "scale(" + scale_f + ") ");
            if (parseInt(view_width) < parseInt(width)) {
                if (scale_f < 1) {
                    window.scrollTo(width, height);
                }
            }
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
    console.log("bbbb");
    console.log(key);
    if ("appJsInterface" in window) {
        try {
            var json_str = appJsInterface.getStorage(key);
            if (json_str == "") {
                return null;
            }
            return unescape(json_str);
        } catch(e) {
            console.log(e);
        }
    } else {
        if (!window.tyrano_save[key] || window.tyrano_save[key] == "") {
            return null;
        } else {
            return decodeURIComponent(window.tyrano_save[key]);
        }
    }
}
$.openWebFromApp = function(url){
    if ("appJsInterface" in window) {
        appJsInterface.openUrl(url);
    } else {
        location.href = 'tyranoplayer-web://?url=' + url;
    }
}
setTimeout(function(){
    (function() {
        var player_back_cnt = 0;
        var j_menu_button = $("<div id='player_menu_button' class='player_menu_area' style='display:none;z-index:999999;opacity:0.68;width:54px;height:54px;line-height:54px;text-align:center;border-radius:50%;cursor:pointer;position:absolute;left:14px;top:14px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>菜单</span></div>");
        var j_back_button = $("<div class='player_menu_area' id='player_back_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:14px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>关闭菜单</span></div>");
        var j_end_button = $("<div class='player_menu_area' id='player_end_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:64px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>回到标题</span></div>");
        var j_auto_button = $("<div class='player_menu_area' id='player_auto_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:114px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>自动</span></div>");
        var j_skip_button = $("<div class='player_menu_area' id='player_skip_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:164px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>快进</span></div>");
        var j_save_button = $("<div class='player_menu_area' id='player_save_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:214px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>存档</span></div>");
        var j_load_button = $("<div class='player_menu_area' id='player_load_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:264px;background-color:#007AFF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>读档</span></div>");
        var j_close_button = $("<div class='player_menu_area' id='player_close_button' style='display:none;z-index:999999;opacity:0.72;padding:10px 14px;border-radius:999px;cursor:pointer;position:absolute;left:14px;top:314px;background-color:#0A84FF;box-shadow:0 4px 14px rgba(0,0,0,0.35);'><span style='color:white;font-size:14px;font-weight:bold'>结束</span></div>");
        function hide_menu(){
            j_end_button.show();
            j_load_button.show();
            j_save_button.show();
            j_auto_button.show();
            j_skip_button.show();
            j_back_button.show();
            j_close_button.show();
            j_menu_button.hide();
        }
        function hide_all_menu(){
            j_end_button.hide();
            j_load_button.hide();
            j_save_button.hide();
            j_auto_button.hide();
            j_skip_button.hide();
            j_back_button.hide();
            j_menu_button.hide();
            j_close_button.hide();
        }
        j_menu_button.click(function(e) {
            hide_menu();
        });
        function menu_show(){
            j_menu_button.show();
            j_end_button.hide();
            j_load_button.hide();
            j_save_button.hide();
            j_auto_button.hide();
            j_skip_button.hide();
            j_back_button.hide();
            j_close_button.hide();
        }
        j_end_button.click(function(e) {
            menu_show();
            if ("appJsInterface" in window) {
                appJsInterface.finishGame();
            } else {
                location.href = "tyranoplayer-back://endgame";
            }
            e.stopPropagation();
        });
        j_save_button.click(function(e) {
            menu_show();
            TYRANO.kag.menu.displaySave();
            e.stopPropagation();
        });
        j_load_button.click(function(e) {
            menu_show();
            TYRANO.kag.menu.displayLoad();
            e.stopPropagation();
        });
        j_auto_button.click(function(e) {
            menu_show();
            TYRANO.kag.ftag.startTag("autostart", {});
            e.stopPropagation();
        });
        j_skip_button.click(function(e) {
            menu_show();
            TYRANO.kag.ftag.startTag("skipstart", {});
            e.stopPropagation();
        });
        j_back_button.click(function(e) {
            menu_show();
            e.stopPropagation();
        })
        j_close_button.click(function(e) {
            if ("appJsInterface" in window) {
                appJsInterface.closeGame();
            } else {
                location.href = "tyranoplayer-back://endgame";
            }
            e.stopPropagation();
        })
        $("body").append(j_menu_button);
        $("body").append(j_end_button);
        $("body").append(j_auto_button);
        $("body").append(j_skip_button);
        $("body").append(j_save_button);
        $("body").append(j_load_button);
        $("body").append(j_back_button);
        $("body").append(j_close_button);
        menu_show();
        $("#tyrano_base").on("click.player", function() {
            hide_all_menu();
            player_back_cnt = 0;
        });
        setInterval(function() {
            if (player_back_cnt == 2) {
                menu_show();
            }
            player_back_cnt++;
        }, 1000);
    })();
}, 1000);
$.userenv = function(){return 'pc';}
