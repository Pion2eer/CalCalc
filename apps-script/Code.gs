/**
 * CalCalc shared AI script. Deploy as a web app: Execute as "Me", access "Anyone".
 * Nothing to configure: the script owner may use the AI; other e-mails go in the optional script property
 *   ALLOWED — comma-separated e-mails ("*" = anyone signed in with Google).
 * The PC registers itself every 4 minutes: POST ?action=localReg {url, token, model, paused}. The first
 * registration pins the PC's proxy token; after that only the same token may move the address
 * (to change PCs, delete the PC_TOKEN property).
 *
 * Everything that happens is written to the spreadsheet «CalCalc AI журнал» on the owner's Drive, and a
 * trigger checks the PC every 10 minutes with a test request, so problems can be seen without a phone.
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
  var email = caller_(b.accessToken) || '?';
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

/** Who is calling: the Google access token from the app, checked with Google itself. */
function caller_(accessToken) {
  if (!accessToken) return null;
  var cache = CacheService.getScriptCache();
  var hit = cache.get('t:' + accessToken.slice(-40));
  if (hit) return hit;
  var r = UrlFetchApp.fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(accessToken), { muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) return null;
  var info = JSON.parse(r.getContentText());
  if (!info.email || String(info.email_verified) !== 'true') return null;
  var email = String(info.email).toLowerCase();
  cache.put('t:' + accessToken.slice(-40), email, 600);
  return email;
}

function allowed_(email) {
  var list = String(PropertiesService.getScriptProperties().getProperty('ALLOWED') || '')
    .toLowerCase().split(',').map(function (s) { return s.trim(); }).filter(String);
  var owner = String(Session.getEffectiveUser().getEmail() || '').toLowerCase();
  return email === owner || list.indexOf('*') >= 0 || list.indexOf(email) >= 0;
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
  var email = caller_(b.accessToken);
  if (!email) {
    log_('parse', '?', 'токен Google не прошёл проверку');
    return { ok: false, error: 'Войдите через Google заново' };
  }
  if (!allowed_(email)) {
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
  };
}

function num_(v) { var n = Number(v); return isFinite(n) ? Math.round(n * 10) / 10 : null; }

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
