
StorageManager.saveObject = function(saveName, object) {
    return this.objectToJson(object)
        .then(json => window.saveDataManager.Save(saveName, json));
};
StorageManager.loadObject = function(saveName) {
    return new Promise((resolve, reject) => {
        const data = window.saveDataManager.Load(saveName);
        if (data) {
            resolve(data);
        } else {
            reject(new Error("Savefile not found"));
        }
    })
        .then(json => StorageManager.jsonToObject(json));
};
StorageManager.exists = function(saveName) {
    return window.saveDataManager.Exists(saveName);
};
SceneManager.isGameActive = function() {
    return true;
};
