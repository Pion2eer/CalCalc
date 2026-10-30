/**
 * CalCalc shared AI script. Deploy as a web app: Execute as "Me", access "Anyone".
 * Nothing to configure: the script owner may use the AI; other e-mails go in the optional script property
 *   ALLOWED — extra comma-separated e-mails ("*" = anyone signed in with Google). Not needed for test users of the
 *   app's OAuth client: their tokens are let in (APP_CLIENT, learned from the owner's app).
 * The PC registers itself every 4 minutes: POST ?action=localReg {url, token, model, paused}. The first
 * registration pins the PC's proxy token; after that only the same token may move the address
 * (to change PCs, delete the PC_TOKEN property).
 *
 * Everything that happens is written to the spreadsheet «CalCalc AI журнал» on the owner's Drive, and a
 * trigger checks the PC every 10 minutes with a test request, so problems can be seen without a phone.
 *
 * Friends and family live in the spreadsheet «CalCalc друзья» on the owner's Drive: who has used the app (to find
 * people by e-mail), the links between them, and the food of everyone with at least one accepted link, which the app
 * sends after each sync. Food is shown only to people with an accepted link.
 */

function doPost(e) {
  var p = (e && e.parameter) || {};
  var body = {};
  try { body = JSON.parse((e.postData && e.postData.contents) || '{}'); } catch (x) {}
  var action = p.action || body.action;
  try {
    if (action === 'localReg') return out_(localReg_(body));
    if (action === 'parse') return out_(parse_(body));
    if (action === 'clientLog') return out_(clientLog_(body));
    if (action === 'social') return out_(social_(body));
    return out_({ ok: false, error: 'unknown action' });
  } catch (err) {
    log_('error', '', action + ': ' + err);
    return out_({ ok: false, retry: true, error: String(err) });
  }
}

function doGet() {
  ensureTrigger_();
  var pc = pc_();
  return out_({ ok: true, app: 'CalCalc', pcOnline: !!(pc && fresh_(pc)), log: logUrl_() });
}

// ---- journal ----

function logSheet_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('LOG_ID');
  var ss = null;
  if (id) try { ss = SpreadsheetApp.openById(id); } catch (x) { ss = null; }
  if (!ss) {
    ss = SpreadsheetApp.create('CalCalc AI журнал');
    ss.getSheets()[0].appendRow(['Время', 'Событие', 'Кто', 'Подробности', 'мс']);
    props.setProperty('LOG_ID', ss.getId());
  }
  return ss.getSheets()[0];
}

function logUrl_() {
  try { return logSheet_().getParent().getUrl(); } catch (x) { return null; }
}

/** One row per event; the oldest rows go once there are more than 3000. */
function log_(event, who, details, ms) {
  try {
    var sh = logSheet_();
    sh.appendRow([new Date(), event, who || '', String(details || '').slice(0, 1000), ms == null ? '' : ms]);
    var n = sh.getLastRow();
    if (n > 3200) sh.deleteRows(2, n - 3000);
  } catch (x) {}
}

// ---- self-test every 10 minutes ----

function ensureTrigger_() {
  var has = ScriptApp.getProjectTriggers().some(function (t) { return t.getHandlerFunction() === 'selfTest'; });
  if (!has) {
    ScriptApp.newTrigger('selfTest').timeBased().everyMinutes(10).create();
    log_('setup', '', 'самопроверка включена, раз в 10 минут');
  }
}

/** Asks the PC about a known food and writes how it went; run by the trigger, or by hand from the editor. */
function selfTest() {
  logSheet_(); // outside log_'s try, so a missing permission shows up in the editor instead of failing silently
  ensureTrigger_();
  var pc = pc_();
  if (!pc) return log_('selftest', '', 'ПК ни разу не регистрировался');
  var age = Math.round((Date.now() - pc.at) / 60000);
  if (!fresh_(pc)) return log_('selftest', '', 'ПК не на связи, последняя регистрация ' + age + ' мин назад');
  if (pc.paused) return log_('selftest', '', 'ИИ на паузе');
  var t0 = Date.now();
  var a = askPc_(pc, 'яблоко 150 г');
  log_('selftest', '', a.ok ? 'ок: ' + a.title + ', ' + a.kcal + ' ккал' : 'ошибка: ' + a.error, Date.now() - t0);
}

/** Run by hand from the editor after a prompt change: a few known foods, results go to the journal. */
/** Sends a drawn plate with an apple and a banana to the PC from the editor: shows whether the model on the PC understands pictures. */
function testImage() {
  var pc = pc_();
  var url = 'https://raw.githubusercontent.com/Pion2eer/CalCalc/main/apps-script/test/plate.jpg';
  var img = Utilities.base64Encode(UrlFetchApp.fetch(url).getBlob().getBytes());
  var t0 = Date.now();
  var a = askPc_(pc, 'Еда на фото', img);
  log_('test', '', '[фото] ' + (a.ok ? a.title + ', ' + a.kcal + ' ккал' + (a.question ? ', вопрос: ' + a.question : '') : 'ошибка: ' + a.error), Date.now() - t0);
}

function testAi() {
  var pc = pc_();
  ['Водка 200 мл', 'Водка 200 мл\nУточнение: Какая крепость водки? — 40%', 'Суп томям с курицей тарелка',
    'Кусочек белого хлеба, пюре 200 г и две сосиски', 'Половину круассана сладкого с кремом и две кружки растворимого кофе',
    'гречка 300 г'].forEach(function (t) {
    var t0 = Date.now();
    var a = askPc_(pc, t);
    log_('test', '', t + ' → ' + (a.ok ? a.title + ', ' + a.kcal + ' ккал, Б' + a.protein + ' Ж' + a.fat + ' У' + a.carbs +
      (a.question ? ', вопрос: ' + a.question : '') : 'ошибка: ' + a.error), Date.now() - t0);
  });
}

function clientLog_(b) {
  var who = caller_(b.accessToken);
  var email = who ? who.email : '?';
  log_('app', email, b.message);
  return { ok: true };
}

function localReg_(b) {
  var props = PropertiesService.getScriptProperties();
  if (!b.token || !b.url) return { ok: false, error: 'bad request' };
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var pinned = props.getProperty('PC_TOKEN');
    if (!pinned) props.setProperty('PC_TOKEN', b.token);
    else if (pinned !== b.token) return { ok: false, error: 'bad token' };
  } finally { lock.releaseLock(); }
  var old = pc_();
  if (!old || old.url !== String(b.url).replace(/\/+$/, '') || !fresh_(old)) log_('pc', '', 'ПК на связи: ' + b.url + (b.paused ? ' (пауза)' : ''));
  props.setProperty('PC', JSON.stringify({
    url: String(b.url || '').replace(/\/+$/, ''), token: b.token, model: b.model || 'gemma4:12b-it-qat',
    paused: !!b.paused, at: Date.now(),
  }));
  return { ok: true };
}

function pc_() {
  var s = PropertiesService.getScriptProperties().getProperty('PC');
  return s ? JSON.parse(s) : null;
}

// the PC re-registers every 4 minutes; older than 10 means it is off
function fresh_(pc) { return Date.now() - pc.at < 10 * 60 * 1000; }

/**
 * Who is calling: the Google access token from the app, checked with Google itself. Returns {email, app}, where app is
 * the OAuth client the token was issued to (the CalCalc Android app for real users).
 */
function caller_(accessToken) {
  if (!accessToken) return null;
  var cache = CacheService.getScriptCache();
  var hit = cache.get('u:' + accessToken.slice(-40));
  if (hit) return JSON.parse(hit);
  var r = UrlFetchApp.fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(accessToken), { muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) return null;
  var info = JSON.parse(r.getContentText());
  if (!info.email || String(info.email_verified) !== 'true') return null;
  var who = { email: String(info.email).toLowerCase(), app: String(info.azp || info.aud || '') };
  cache.put('u:' + accessToken.slice(-40), JSON.stringify(who), 600);
  return who;
}

/**
 * Who may use the AI. The CalCalc OAuth client is in testing mode, so only the test users added in the Google Cloud
 * console can get a token from it at all: a token issued to that client is enough, no second list to keep. The
 * client is learned from the first call of the owner or of someone in ALLOWED. ALLOWED still adds e-mails by hand.
 */
function allowed_(who) {
  var props = PropertiesService.getScriptProperties();
  var owner = String(Session.getEffectiveUser().getEmail() || '').toLowerCase();
  if (who.email === owner) {
    if (who.app && props.getProperty('APP_CLIENT') !== who.app) props.setProperty('APP_CLIENT', who.app);
    return true;
  }
  var app = props.getProperty('APP_CLIENT');
  if (app && who.app === app) return true;
  var list = String(props.getProperty('ALLOWED') || '')
    .toLowerCase().split(',').map(function (s) { return s.trim(); }).filter(String);
  // someone listed by hand uses the same app, so their token teaches the client too, without waiting for the owner
  if (list.indexOf(who.email) >= 0) {
    if (!app && who.app) props.setProperty('APP_CLIENT', who.app);
    return true;
  }
  return list.indexOf('*') >= 0;
}

// A small model is bad at multiplying in its head: it gives grams and kcal per 100 g for each item, the script does the sums
var PROMPT =
  'Ты нутрициолог. Пользователь пишет, что съел или выпил. Разбери запись на продукты.\n' +
  'Для каждого продукта оцени вес в граммах (для напитков мл ≈ г) и калорийность и БЖУ на 100 г по известным справочным данным.\n' +
  'Если порция не указана, бери типичную порцию. Алкоголь: этанол 7 ккал на грамм, водка 40% — 235 ккал на 100 мл.\n' +
  'Задай ОДИН уточняющий вопрос, только если ответ меняет калорийность больше чем на 30% ' +
  '(например, размер порции, жирность, с сахаром или без). Дай 2-4 коротких варианта ответа.\n' +
  'Если в записи уже есть строка «Уточнение», вопросов больше не задавай.\n' +
  'Ответь только JSON без пояснений:\n' +
  '{"title":"короткое название, по-русски","items":[{"name":"продукт","grams":число,"kcal100":число,' +
  '"protein100":число,"fat100":число,"carbs100":число}],"question":"вопрос или пустая строка","options":["вариант",...]}';

function parse_(b) {
  var who = caller_(b.accessToken);
  if (!who) {
    log_('parse', '?', 'токен Google не прошёл проверку');
    return { ok: false, error: 'Войдите через Google заново' };
  }
  var email = who.email;
  seen_(email);
  if (!allowed_(who)) {
    log_('parse', email, 'нет в списке ALLOWED');
    return { ok: false, error: 'Нет доступа к ИИ для ' + email };
  }
  var text = String(b.text || '').slice(0, 1000);
  // a photo of the food comes as base64 JPEG, with the user's words (if any) as a hint
  var image = b.image ? String(b.image) : null;
  if (image && image.length > 3000000) return { ok: false, error: 'Фото слишком большое' };
  if (!text && !image) return { ok: false, error: 'empty' };
  if (!text) text = 'Еда на фото';

  var pc = pc_();
  if (!pc || !fresh_(pc) || pc.paused) {
    var why = !pc ? 'ПК ни разу не регистрировался' : pc.paused ? 'ИИ на паузе' : 'ПК не на связи ' + Math.round((Date.now() - pc.at) / 60000) + ' мин';
    log_('parse', email, text + ' → ' + why);
    return { ok: false, retry: true, error: 'Компьютер с ИИ недоступен' };
  }
  var t0 = Date.now();
  var res = askPc_(pc, text, image);
  log_('parse', email, (image ? '[фото] ' : '') + text + ' → ' + (res.ok ? res.title + ', ' + res.kcal + ' ккал' + (res.question ? ', вопрос: ' + res.question : '') : 'ошибка: ' + res.error + (res.detail ? ' (' + res.detail + ')' : '')), Date.now() - t0);
  delete res.detail;
  return res;
}

function askPc_(pc, text, image) {
  var user = { role: 'user', content: image ? 'На фото еда. Определи продукты и оцени вес каждого по фото. ' + text : text };
  if (image) user.images = [image];
  var r;
  try {
    r = UrlFetchApp.fetch(pc.url + '/chat', {
      method: 'post', contentType: 'application/json', muteHttpExceptions: true,
      headers: { Authorization: 'Bearer ' + pc.token },
      payload: JSON.stringify({
        // gemma4 thinks before answering unless told not to; with thinking a short answer took minutes
        model: pc.model, stream: false, format: 'json', think: false, keep_alive: '24h',
        options: { temperature: 0.2, num_predict: 500 },
        messages: [{ role: 'system', content: PROMPT }, user],
      }),
    });
  } catch (x) {
    // the phone gets a short reason; the technical one goes to the journal
    return { ok: false, retry: true, error: 'Компьютер с ИИ не ответил, попробую ещё раз', detail: String(x).slice(0, 150) };
  }
  var code = r.getResponseCode();
  if (code !== 200) return { ok: false, retry: code >= 500 || code === 429, error: 'ИИ: HTTP ' + code + ' ' + r.getContentText().slice(0, 150) };

  var a;
  try {
    var msg = JSON.parse(r.getContentText()).message.content;
    a = JSON.parse(msg.slice(msg.indexOf('{'), msg.lastIndexOf('}') + 1));
  } catch (x) {
    return { ok: false, error: 'ИИ ответил непонятно: ' + r.getContentText().slice(0, 150) };
  }
  if (Array.isArray(a.items) && a.items.length) {
    var sum = function (k) {
      return a.items.reduce(function (t, it) { return t + (Number(it.grams) || 0) * (Number(it[k]) || 0) / 100; }, 0);
    };
    a.kcal = sum('kcal100'); a.protein = sum('protein100'); a.fat = sum('fat100'); a.carbs = sum('carbs100');
  }
  var kcal = Math.round(Number(a.kcal));
  if (!isFinite(kcal) || kcal < 0 || kcal > 10000) return { ok: false, error: 'ИИ не смог оценить' };
  var q = a.question ? String(a.question).trim() : '';
  var opts = Array.isArray(a.options) ? a.options.map(String).slice(0, 4) : [];
  return {
    ok: true, title: a.title ? String(a.title).slice(0, 80) : null, kcal: kcal,
    protein: num_(a.protein), fat: num_(a.fat), carbs: num_(a.carbs),
    question: q && opts.length ? q : null, options: q && opts.length ? opts : [],
    // what the sum is made of, so the user can see a wrong guess at once
    items: Array.isArray(a.items) ? a.items.slice(0, 12).map(function (it) {
      var g = Math.round(Number(it.grams) || 0);
      return { name: String(it.name || '').slice(0, 60), grams: g, kcal: Math.round(g * (Number(it.kcal100) || 0) / 100) };
    }) : [],
  };
}

function num_(v) { var n = Number(v); return isFinite(n) ? Math.round(n * 10) / 10 : null; }

// ---- friends and family ----

/** Run from the editor: two made-up people invite, accept, share food, see it, and part again. */
function testSocial() {
  var a = 'test-a@calcalc.test', b = 'test-b@calcalc.test';
  seen_(a); seen_(b);
  var r = [];
  r.push(socialFor_(a, { op: 'invite', email: b, kind: 'family' }));
  r.push(socialFor_(b, { op: 'feed', email: a }));
  r.push(socialFor_(b, { op: 'list' }));
  r.push(socialFor_(b, { op: 'answer', email: a, accept: true }));
  r.push(socialFor_(a, { op: 'push', entries: [{ day: '2026-10-30', at: Date.now(), title: 'Тест борщ', kcal: 400 }] }));
  r.push(socialFor_(b, { op: 'feed', email: a }));
  r.push(socialFor_(a, { op: 'remove', email: b }));
  r.push(socialFor_(b, { op: 'feed', email: a }));
  var users = socialBook_().getSheetByName('users');
  var rows = users.getDataRange().getValues();
  for (var i = rows.length - 1; i >= 1; i--) if (rows[i][0] === a || rows[i][0] === b) users.deleteRow(i + 1);
  CacheService.getScriptCache().removeAll(['seen:' + a, 'seen:' + b]);
  Logger.log(JSON.stringify(r));
  return r;
}

function socialBook_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('SOCIAL_ID');
  var ss = null;
  if (id) try { ss = SpreadsheetApp.openById(id); } catch (x) { ss = null; }
  if (!ss) {
    ss = SpreadsheetApp.create('CalCalc друзья');
    ss.getSheets()[0].setName('users').appendRow(['E-mail', 'Последний вход']);
    ss.insertSheet('links').appendRow(['От', 'Кому', 'Кто', 'Статус', 'Когда']);
    props.setProperty('SOCIAL_ID', ss.getId());
  }
  return ss;
}

/** Remembers who uses the app, so others can find them by e-mail; written at most every 6 hours per person. */
function seen_(email) {
  var cache = CacheService.getScriptCache();
  if (cache.get('seen:' + email)) return;
  try {
    var sh = socialBook_().getSheetByName('users');
    var rows = sh.getDataRange().getValues();
    for (var i = 1; i < rows.length; i++) {
      if (rows[i][0] === email) { sh.getRange(i + 1, 2).setValue(new Date()); cache.put('seen:' + email, '1', 21600); return; }
    }
    sh.appendRow([email, new Date()]);
    cache.put('seen:' + email, '1', 21600);
  } catch (x) {}
}

/** Seen by the friends book, or at least in the AI journal (people who used the app before friends existed). */
function known_(email) {
  var rows = socialBook_().getSheetByName('users').getDataRange().getValues();
  for (var i = 1; i < rows.length; i++) if (rows[i][0] === email) return true;
  var log = logSheet_().getDataRange().getValues();
  for (var j = 1; j < log.length; j++) if (String(log[j][2]).toLowerCase() === email) { seen_(email); return true; }
  return false;
}

/** Links as [{row, from, to, kind, status}]; a link is one row whoever sent the invite. */
function links_() {
  var rows = socialBook_().getSheetByName('links').getDataRange().getValues();
  var out = [];
  for (var i = 1; i < rows.length; i++) out.push({ row: i + 1, from: rows[i][0], to: rows[i][1], kind: rows[i][2], status: rows[i][3] });
  return out;
}

function linkOf_(a, b) {
  return links_().filter(function (l) { return (l.from === a && l.to === b) || (l.from === b && l.to === a); })[0] || null;
}

function friendsOf_(email) {
  return links_().filter(function (l) { return l.status === 'accepted' && (l.from === email || l.to === email); });
}

function foodSheet_(email, create) {
  var ss = socialBook_();
  var name = 'food:' + email;
  var sh = ss.getSheetByName(name);
  if (!sh && create) sh = ss.insertSheet(name);
  return sh;
}

function social_(b) {
  var who = caller_(b.accessToken);
  if (!who) return { ok: false, error: 'Войдите через Google заново' };
  if (!allowed_(who)) return { ok: false, error: 'Нет доступа для ' + who.email };
  seen_(who.email);
  return socialFor_(who.email, b);
}

function socialFor_(me, b) {
  var other = String(b.email || '').trim().toLowerCase();
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    switch (b.op) {
      case 'list':
        return { ok: true, links: links_().filter(function (l) { return l.from === me || l.to === me; }).map(function (l) {
          return { email: l.from === me ? l.to : l.from, kind: l.kind, status: l.status, incoming: l.to === me };
        }) };
      case 'invite': {
        if (!other || other === me) return { ok: false, error: 'Введите e-mail другого человека' };
        // someone not seen yet still gets the invite: they find it in the profile once they open the new version
        var unknown = !known_(other);
        var kind = b.kind === 'family' ? 'family' : 'friend';
        var l = linkOf_(me, other);
        var sh = socialBook_().getSheetByName('links');
        if (!l) {
          sh.appendRow([me, other, kind, 'pending', new Date()]);
          log_('social', me, 'приглашение для ' + other + ' (' + kind + ')' + (unknown ? ', ещё не входил' : ''));
        } else if (l.status === 'pending' && l.to === me) {
          // they invited me first: inviting back is the same as accepting
          sh.getRange(l.row, 4, 1, 2).setValues([['accepted', new Date()]]);
          log_('social', me, 'принял ' + other);
        }
        return { ok: true };
      }
      case 'answer': {
        var a = linkOf_(me, other);
        if (!a || a.to !== me || a.status !== 'pending') return { ok: false, error: 'Приглашения нет' };
        var links = socialBook_().getSheetByName('links');
        if (b.accept) links.getRange(a.row, 4, 1, 2).setValues([['accepted', new Date()]]);
        else links.deleteRow(a.row);
        log_('social', me, (b.accept ? 'принял ' : 'отклонил ') + other);
        return { ok: true };
      }
      case 'remove': {
        var r = linkOf_(me, other);
        if (r) socialBook_().getSheetByName('links').deleteRow(r.row);
        log_('social', me, 'убрал ' + other);
        if (!friendsOf_(me).length) { var f0 = foodSheet_(me, false); if (f0) socialBook_().deleteSheet(f0); }
        return { ok: true };
      }
      case 'push': {
        // the food is kept only while someone may look at it
        if (!friendsOf_(me).length) {
          var f1 = foodSheet_(me, false);
          if (f1) socialBook_().deleteSheet(f1);
          return { ok: true, shared: false };
        }
        var list = Array.isArray(b.entries) ? b.entries.slice(0, 600) : [];
        var fs = foodSheet_(me, true);
        fs.clearContents();
        if (list.length) fs.getRange(1, 1, list.length, 1).setValues(list.map(function (e) { return [JSON.stringify(e).slice(0, 45000)]; }));
        return { ok: true, shared: true };
      }
      case 'feed': {
        var link = linkOf_(me, other);
        if (!link || link.status !== 'accepted') return { ok: false, error: 'Нет доступа к еде ' + other };
        var food = foodSheet_(other, false);
        var rows = food && food.getLastRow() ? food.getRange(1, 1, food.getLastRow(), 1).getValues() : [];
        return { ok: true, entries: rows.map(function (r) { try { return JSON.parse(r[0]); } catch (x) { return null; } }).filter(Boolean) };
      }
    }
    return { ok: false, error: 'unknown op' };
  } finally { lock.releaseLock(); }
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
