/**
 * Host API prelude for JavaScript plugins.
 *
 * Evaluated inside the plugin's Rhino scope *before* `server.js`, so a plugin author writes plain
 * ES5/ES6 against `legado.*` and never has to know how the bridge works. Every method funnels into
 * the single `__legadoCall(name, args)` entry point injected by the host, which keeps the Kotlin
 * side tiny: adding a host capability means adding one operation there, not a new binding here.
 *
 * `__legadoCall` and `__legadoMeta` are injected as globals by `JsPluginRuntime`.
 */
var legado = (function () {
  function call(name, args) {
    return __legadoCall(name, args);
  }

  /** Turns a host operation name into a variadic function. */
  function bind(name) {
    return function () {
      return call(name, Array.prototype.slice.call(arguments));
    };
  }

  var api = {};

  api.pluginId = __legadoMeta.pluginId;
  api.pluginName = __legadoMeta.pluginName;
  api.pluginVersion = __legadoMeta.pluginVersion;
  api.apiVersion = __legadoMeta.apiVersion;

  api.log = {
    debug: bind('log.debug'),
    info: bind('log.info'),
    warn: bind('log.warn'),
    error: bind('log.error')
  };

  api.storage = {
    get: bind('storage.get'),
    set: bind('storage.set'),
    remove: bind('storage.remove'),
    keys: bind('storage.keys'),
    clear: bind('storage.clear'),
    getJson: bind('storage.getJson'),
    setJson: bind('storage.setJson')
  };

  api.settings = {
    all: bind('settings.all'),
    update: bind('settings.update'),
    replace: bind('settings.replace'),
    get: bind('settings.get')
  };

  api.books = {
    listShelf: bind('books.listShelf'),
    getShelfItem: bind('books.getShelfItem'),
    addToShelf: bind('books.addToShelf'),
    removeFromShelf: bind('books.removeFromShelf'),
    setCompleted: bind('books.setCompleted'),
    getProgress: bind('books.getProgress'),
    saveProgress: bind('books.saveProgress'),
    getToc: bind('books.getToc'),
    getCachedChapters: bind('books.getCachedChapters'),
    listCachedChapterUrls: bind('books.listCachedChapterUrls'),
    getCachedContent: bind('books.getCachedContent'),
    requestOfflineCache: bind('books.requestOfflineCache'),
    cancelOfflineCache: bind('books.cancelOfflineCache'),
    search: bind('books.search'),
    fetchDetails: bind('books.fetchDetails'),
    fetchChapters: bind('books.fetchChapters'),
    fetchContent: bind('books.fetchContent')
  };

  api.sources = {
    list: bind('sources.list'),
    get: bind('sources.get'),
    save: bind('sources.save'),
    remove: bind('sources.remove'),
    setEnabled: bind('sources.setEnabled'),
    export: bind('sources.export')
  };

  api.subscriptions = {
    list: bind('subscriptions.list'),
    add: bind('subscriptions.add'),
    remove: bind('subscriptions.remove'),
    refresh: bind('subscriptions.refresh'),
    refreshAll: bind('subscriptions.refreshAll')
  };

  api.covers = {
    cacheUrl: bind('covers.cacheUrl'),
    contentType: bind('covers.contentType'),
    delete: bind('covers.delete')
  };

  api.http = {
    request: bind('http.request'),
    get: bind('http.get'),
    post: bind('http.post')
  };

  api.auth = {
    verifyAdminPassword: bind('auth.verifyAdminPassword')
  };

  api.events = {
    emit: bind('events.emit')
  };

  /**
   * Registers a handler at /api/plugins/<id>/r/<path>.
   * The handler receives a request object and may return a string, a JSON-serialisable value,
   * or a descriptor like { status, headers, contentType, body, bodyBase64 }.
   */
  api.route = function (method, path, handler) {
    return call('route.register', [method, path, false, handler]);
  };

  /** Same as `route`, but reachable without an admin session (plugin must authenticate callers). */
  api.routePublic = function (method, path, handler) {
    return call('route.register', [method, path, true, handler]);
  };

  api.on = function (eventType, handler) {
    return call('events.on', [eventType, handler]);
  };

  /** Returns a numeric handle usable with `legado.unschedule`. */
  api.schedule = function (initialDelayMs, periodMs, task) {
    return call('schedule.register', [initialDelayMs, periodMs, task]);
  };

  api.unschedule = function (handle) {
    return call('schedule.cancel', [handle]);
  };

  return api;
})();
