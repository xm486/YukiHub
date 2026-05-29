Graphics._createRenderer = function() {
    PIXI.dontSayHello = true;
    var width = this._width;
    var height = this._height;
    var options = { view: this._canvas };

    function getUrlParameters(url) {
        if (!url) url = window.location.href;
        var result = {};
        var parts = url.replace(/[?&]+([^=&]+)=([^&]*)/gi, function(m,key,value) {
            result[key] = value;
        });
        return result;
    }

    var param = getUrlParameters();

    if ("android-legacy" in param) {
        console.log("Android loader enabled.");
        console.log("Add options to the PIXI renderer.");

        const AndroidLegacyOption = {
            legacy: true
        };

        for (var optkey in AndroidLegacyOption) {
            options[optkey] = AndroidLegacyOption[optkey];
            console.log(`Option added : ${"$"}{optkey} => ${"$"}{options[optkey]}`);
        }
    } else
        console.log("Android loader has been disabled. (Not a legacy device or running in desktop)");

    try {

    switch (this._rendererType) {
        case 'canvas':
            this._renderer = new PIXI.CanvasRenderer(width, height, options);
            break;
        case 'webgl':
            this._renderer = new PIXI.WebGLRenderer(width, height, options);
            break;
        default:
            this._renderer = PIXI.autoDetectRenderer(width, height, options);
            break;
        }

        if(this._renderer && this._renderer.textureGC)
            this._renderer.textureGC.maxIdle = 1;

        console.log(typeof this._renderer);

    } catch (e) {
        this._renderer = null;
    }
};

StorageManager.saveToWebStorage = function(savefileId, json) {
    var key = this.webStorageKey(savefileId);
    var data = LZString.compressToBase64(json);
    window.saveDataManager.Save(key, data);
};

StorageManager.loadFromWebStorage = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    return LZString.decompressFromBase64(window.saveDataManager.Load(key));
};

StorageManager.loadFromWebStorageBackup = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    return LZString.decompressFromBase64(window.saveDataManager.Load(key));
};

StorageManager.webStorageBackupExists = function(savefileId) {
    var key = this.webStorageKey(savefileId) + "bak";
    return window.saveDataManager.Exists(key);
};

StorageManager.webStorageExists = function(savefileId) {
    var key = this.webStorageKey(savefileId);
    return window.saveDataManager.Exists(key);
};
Utils.isMobileDevice = function() {return false;};
SceneManager.shouldUseCanvasRenderer = function() {return true;};
Graphics._defaultStretchMode = function() {return true;};
document.body.parentNode.style.overflow = "hidden";

// RPG Touch Pad
window.addEventListener('load', () => {
  const padSize = window.innerHeight * 0.4
  const joyStickSR = padSize * 0.5
  const joyStickR = joyStickSR * 0.4
  const allMargin = 10
  const lrMargin = 50
  const joyStickCX = joyStickSR + allMargin + lrMargin
  const joyStickCY = window.innerHeight - joyStickSR - allMargin
  let isKeysShown = true
  let useJoyStick = true
  let useDir8 = false
  const udlrEvents = {
    Up: false,
    Left: false,
    Right: false,
    Down: false
  }
  const joyStickStage = document.createElement('div')
  const joyStick = document.createElement('div')
  const actionsElement = document.createElement('div')
  const keySwitchElement = document.createElement('div')
  keySwitchElement.innerText = isKeysShown ? 'Hide' : 'Show'
  const joyStickSwitchElement = document.createElement('div')
  joyStickSwitchElement.innerText = useJoyStick ? 'Button' : 'Stick'
  const dir8SwitchElement = document.createElement('div')
  dir8SwitchElement.innerText = useDir8 ? '4 Dir' : '8 Dir'
  const udlrElement = document.createElement('div')
  const qwzxElement = document.createElement('div')
  document.body.appendChild(actionsElement)
  actionsElement.appendChild(keySwitchElement)
  actionsElement.appendChild(joyStickSwitchElement)
  actionsElement.appendChild(dir8SwitchElement)
  document.body.appendChild(qwzxElement)
  document.body.appendChild(joyStickStage)
  joyStickStage.appendChild(joyStick)
  document.body.appendChild(udlrElement)
  const keyCodes = {
    Tab: 9,
    Enter: 13,
    Shift: 16,
    Ctrl: 17,
    Alt: 18,
    Esc: 27,
    Space: 32,
    PageUp: 33,
    PageDown: 34,
    Left: 37,
    Up: 38,
    Right: 39,
    Down: 40,
    Q: 81,
    W: 87,
    X: 88,
    Z: 90
  }
  const actionsBtns = [
    {
      text: 'PageUp',
      keyCode: keyCodes.PageUp
    },
    {
      text: 'PageDown',
      keyCode: keyCodes.PageDown
    },
    {
      text: 'Tab',
      keyCode: keyCodes.Tab
    },
    {
      text: 'Alt',
      keyCode: keyCodes.Alt
    },
    {
      text: 'Ctrl',
      keyCode: keyCodes.Ctrl
    },
    {
      text: 'Shift',
      keyCode: keyCodes.Shift
    },
    {
      text: 'Space',
      keyCode: keyCodes.Space
    },
    {
      text: 'Enter',
      keyCode: keyCodes.Enter
    },
    {
      text: 'Esc',
      keyCode: keyCodes.Esc
    }
  ]
  const udlrBtns = [
    {
      keyCodes: [keyCodes.Up],
      style: {
        transform: 'translate(-50%,0%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '50%',
        top: '0%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left],
      style: {
        transform: 'translate(0%,-50%) rotate(45deg)',
        borderTopLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        left: '0%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Right],
      style: {
        transform: 'translate(-100%,-50%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        borderTopLeftRadius: '50em',
        left: '100%',
        top: '50%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Down],
      style: {
        transform: 'translate(-50%,-100%) rotate(45deg)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '50%',
        top: '100%',
        width: '40%',
        height: '40%'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Up],
      style: {
        transform: 'translate(0%,0%)',
        borderBottomLeftRadius: '50em',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        left: '0%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Left, keyCodes.Down],
      style: {
        transform: 'translate(0%,-100%)',
        borderTopLeftRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '0%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Up],
      style: {
        transform: 'translate(-100%,0%)',
        borderTopLeftRadius: '50em',
        borderTopRightRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '0%',
        display: useDir8 ? 'block' : 'none'
      }
    },
    {
      keyCodes: [keyCodes.Right, keyCodes.Down],
      style: {
        transform: 'translate(-100%,-100%)',
        borderTopRightRadius: '50em',
        borderBottomLeftRadius: '50em',
        borderBottomRightRadius: '50em',
        left: '100%',
        top: '100%',
        display: useDir8 ? 'block' : 'none'
      }
    }
  ]
  const qwzxBtns = [
    {
      text: 'Q',
      keyCode: keyCodes.Q,
      style: {
        transform: 'translate(0%,-50%)',
        left: '0%',
        top: '50%'
      }
    },
    {
      text: 'W',
      keyCode: keyCodes.W,
      style: {
        transform: 'translate(-50%,0%)',
        left: '50%',
        top: '0%'
      }
    },
    {
      text: 'Z',
      keyCode: keyCodes.Z,
      style: {
        transform: 'translate(-50%,-100%)',
        left: '50%',
        top: '100%'
      }
    },
    {
      text: 'X',
      keyCode: keyCodes.X,
      style: {
        transform: 'translate(-100%,-50%)',
        left: '100%',
        top: '50%'
      }
    }
  ]
  const commonStyle = {
    position: 'absolute',
    zIndex: '99999999'
  }
  const btnStyle = {
    ...commonStyle,
    background: 'rgba(255,150,200,0.4)',
    color: 'rgba(255,255,255,0.3)',
    textAlign: 'center',
    boxShadow: '0 0 10px 0 rgba(255,255,255,0.5)'
  }
  const actionStyle = {
    ...btnStyle,
    width: `${padSize * 0.5}px`,
    height: `${padSize * 0.125}px`,
    lineHeight: `${padSize * 0.125}px`,
    borderRadius: '50em'
  }
  const udlrStyle = {
    ...btnStyle,
    width: '33%',
    height: '33%'
  }
  const qwzxStyle = {
    ...btnStyle,
    width: '40%',
    height: '40%',
    borderRadius: '50em'
  }
  const textStyle = {
    ...commonStyle,
    color: 'rgba(255,255,255,0.3)',
    transform: 'translate(-50%,-50%)',
    left: '50%',
    top: '50%'
  }
  Object.assign(keySwitchElement.style, {
    ...btnStyle,
    width: `${padSize * 0.3}px`,
    height: `${padSize * 0.3}px`,
    lineHeight: `${padSize * 0.3}px`,
    borderRadius: '50em',
    left: `${allMargin}px`,
    top: `${allMargin}px`
  })
  Object.assign(joyStickSwitchElement.style, {
    ...btnStyle,
    width: `${padSize * 0.3}px`,
    height: `${padSize * 0.3}px`,
    lineHeight: `${padSize * 0.3}px`,
    borderRadius: '50em',
    left: `${allMargin}px`,
    top: `${padSize * 0.3 + 5 + allMargin}px`
  })
  Object.assign(dir8SwitchElement.style, {
    ...btnStyle,
    width: `${padSize * 0.3}px`,
    height: `${padSize * 0.3}px`,
    lineHeight: `${padSize * 0.3}px`,
    borderRadius: '50em',
    left: `${allMargin}px`,
    top: `${padSize * 0.6 + 10 + allMargin}px`
  })
  Object.assign(joyStickStage.style, {
    ...commonStyle,
    boxShadow: '0 0 10px 0 rgba(255,255,255,0.5)',
    width: `${padSize}px`,
    height: `${padSize}px`,
    transform: 'translate(0%,-100%)',
    borderRadius: '50em',
    left: `${allMargin + lrMargin}px`,
    top: `calc(100% - ${allMargin}px)`,
    display: useJoyStick ? 'block' : 'none'
  })
  Object.assign(joyStick.style, {
    ...btnStyle,
    marginLeft: `${joyStickSR - joyStickR}px`,
    marginTop: `${joyStickSR - joyStickR}px`,
    width: `${2 * joyStickR}px`,
    height: `${2 * joyStickR}px`,
    borderRadius: '50em'
  })
  Object.assign(udlrElement.style, {
    ...commonStyle,
    boxShadow: '0 0 10px 0 rgba(255,255,255,0.5)',
    borderRadius: '50em',
    width: `${padSize}px`,
    height: `${padSize}px`,
    transform: 'translate(0%,-100%)',
    left: `${allMargin + lrMargin}px`,
    top: `calc(100% - ${allMargin}px)`,
    display: useJoyStick ? 'none' : 'block'
  })
  Object.assign(qwzxElement.style, {
    ...commonStyle,
    width: `${padSize}px`,
    height: `${padSize}px`,
    transform: 'translate(-100%,-100%)',
    borderRadius: '50em',
    boxShadow: '0 0 10px 0 rgba(255,255,255,0.5)',
    left: `calc(100% - ${allMargin + lrMargin}px)`,
    top: `calc(100% - ${allMargin}px)`
  })
  const setKeyDownColor = (e) => {
    e.style.background = 'rgba(255,150,200,0.6)'
  }
  const setKeyUpColor = (e) => {
    e.style.background = 'rgba(255,150,200,0.4)'
  }
  const startKeyEvent = (e, keyCode, keyEvent) => {
    const evtObj = document.createEvent('UIEvents')
    Object.defineProperty(evtObj, 'keyCode', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    Object.defineProperty(evtObj, 'which', {
      get: () => {
        return evtObj.keyCodeVal
      }
    })
    evtObj.initUIEvent(keyEvent, true, true, window, 1)
    evtObj.keyCodeVal = keyCode
    e.dispatchEvent(evtObj)
  }
  const setEventStart = (e, keyCodes) => {
    e.addEventListener('touchstart', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyDownColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keydown')
      })
    })
  }
  const setEventMove = (e) => {
    e.addEventListener('touchmove', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
    })
  }
  const setEventEnd = (e, keyCodes) => {
    e.addEventListener('touchend', (evt) => {
      evt.stopPropagation()
      evt.preventDefault()
      setKeyUpColor(e)
      keyCodes.forEach(keyCode => {
        startKeyEvent(e, keyCode, 'keyup')
      })
    })
  }
  const getDistance = (x1, y1, x2, y2) => {
    return Math.sqrt((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2))
  }
  const getAngle = (x1, y1, x2, y2) => {
    let angle = 180 * Math.atan((y1 - y2) / (x1 - x2)) / Math.PI
    if (x1 >= x2 && y1 < y2) angle += 360
    if (x1 < x2) angle += 180
    return angle
  }
  const endMoveEvent = () => {
    for (const key in udlrEvents) {
      if (udlrEvents[key]) {
        udlrEvents[key] = false
        startKeyEvent(joyStick, keyCodes[key], 'keyup')
      }
    }
  }
  const startMoveEvent = (touch) => {
    if (getDistance(touch.clientX, touch.clientY, joyStickCX, joyStickCY) > 20) {
      const angle = getAngle(touch.clientX, touch.clientY, joyStickCX, joyStickCY)
      const events = useDir8 ? {
        Up: angle > 202.5 && angle < 337.5,
        Right: (angle >= 0 && angle < 67.5) || (angle < 360 && angle > 292.5),
        Down: angle > 22.5 && angle < 157.5,
        Left: angle > 112.5 && angle < 247.5
      } : {
        Up: angle > 225 && angle < 315,
        Right: (angle >= 0 && angle < 45) || (angle < 360 && angle > 315),
        Down: angle > 45 && angle < 135,
        Left: angle > 135 && angle < 225
      }
      for (const key in events) {
        if (events[key] && !udlrEvents[key]) {
          udlrEvents[key] = true
          startKeyEvent(joyStick, keyCodes[key], 'keydown')
        }
        if (!events[key] && udlrEvents[key]) {
          udlrEvents[key] = false
          startKeyEvent(joyStick, keyCodes[key], 'keyup')
        }
      }
    } else {
      endMoveEvent()
    }
  }
  keySwitchElement.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyDownColor(keySwitchElement)
  })
  setEventMove(keySwitchElement)
  keySwitchElement.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyUpColor(keySwitchElement)
    if (isKeysShown) {
      if (useJoyStick) joyStickStage.style.display = 'none'
      else udlrElement.style.display = 'none'
      qwzxElement.style.display = 'none'
      for (let i = 1; i < actionsElement.children.length; i++) {
        actionsElement.children.item(i).style.display = 'none'
      }
      keySwitchElement.innerText = 'Show'
      isKeysShown = false
    } else {
      if (useJoyStick) joyStickStage.style.display = 'block'
      else udlrElement.style.display = 'block'
      qwzxElement.style.display = 'block'
      for (let i = 1; i < actionsElement.children.length; i++) {
        actionsElement.children.item(i).style.display = 'block'
      }
      keySwitchElement.innerText = 'Hide'
      isKeysShown = true
    }
  })
  joyStickSwitchElement.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyDownColor(joyStickSwitchElement)
  })
  setEventMove(joyStickSwitchElement)
  joyStickSwitchElement.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyUpColor(joyStickSwitchElement)
    if (useJoyStick) {
      udlrElement.style.display = 'block'
      joyStickStage.style.display = 'none'
      joyStickSwitchElement.innerText = 'Stick'
      useJoyStick = false
    } else {
      udlrElement.style.display = 'none'
      joyStickStage.style.display = 'block'
      joyStickSwitchElement.innerText = 'Button'
      useJoyStick = true
    }
  })
  dir8SwitchElement.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyDownColor(dir8SwitchElement)
  })
  setEventMove(dir8SwitchElement)
  dir8SwitchElement.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    setKeyUpColor(dir8SwitchElement)
    if (useDir8) {
      for (let i = 4; i < udlrElement.children.length; i++) {
        udlrElement.children.item(i).style.display = 'none'
      }
      dir8SwitchElement.innerText = '8 Dir'
      useDir8 = false
    } else {
      for (let i = 4; i < udlrElement.children.length; i++) {
        udlrElement.children.item(i).style.display = 'block'
      }
      dir8SwitchElement.innerText = '4 Dir'
      useDir8 = true
    }
  })
  joyStickStage.addEventListener('touchstart', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    const touch = evt.targetTouches[0]
    joyStick.style.left = `${touch.clientX - joyStickCX}px`
    joyStick.style.top = `${touch.clientY - joyStickCY}px`
    startMoveEvent(touch)
  })
  joyStickStage.addEventListener('touchmove', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    const touch = evt.targetTouches[0]
    const subLen = getDistance(touch.clientX, touch.clientY, joyStickCX, joyStickCY)
    if (subLen > joyStickSR) {
      joyStick.style.left = `${(touch.clientX - joyStickCX) * joyStickSR / subLen}px`
      joyStick.style.top = `${(touch.clientY - joyStickCY) * joyStickSR / subLen}px`
    } else {
      joyStick.style.left = `${touch.clientX - joyStickCX}px`
      joyStick.style.top = `${touch.clientY - joyStickCY}px`
    }
    startMoveEvent(touch)
  })
  joyStickStage.addEventListener('touchend', (evt) => {
    evt.stopPropagation()
    evt.preventDefault()
    joyStick.style.left = '0px'
    joyStick.style.top = '0px'
    endMoveEvent()
  })
  actionsBtns.forEach(it => {
    const childElement = document.createElement('div')
    actionsElement.appendChild(childElement)
    childElement.innerText = it.text
    Object.assign(childElement.style, {
      ...actionStyle,
      right: `${allMargin}px`,
      top: `${actionsBtns.indexOf(it) * (padSize * 0.125 + 5) + allMargin}px`
    })
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
  })
  udlrBtns.forEach(it => {
    const childElement = document.createElement('div')
    udlrElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...udlrStyle,
      ...it.style
    })
    setEventStart(childElement, it.keyCodes)
    setEventMove(childElement)
    setEventEnd(childElement, it.keyCodes)
  })
  qwzxBtns.forEach(it => {
    const childElement = document.createElement('div')
    qwzxElement.appendChild(childElement)
    Object.assign(childElement.style, {
      ...qwzxStyle,
      ...it.style
    })
    setEventStart(childElement, [it.keyCode])
    setEventMove(childElement)
    setEventEnd(childElement, [it.keyCode])
    const tElement = document.createElement('div')
    childElement.appendChild(tElement)
    Object.assign(tElement.style, textStyle)
    tElement.innerText = it.text
  })
})
