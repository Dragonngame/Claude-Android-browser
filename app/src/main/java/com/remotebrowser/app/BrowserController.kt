package com.remotebrowser.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

fun normalizeUrl(input: String): String {
    val s = input.trim()
    if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:")) return s
    if (!s.contains(" ") && s.contains(".")) return "https://$s"
    return "https://www.google.com/search?q=" + Uri.encode(s)
}

private val FIND_JS = """
(function(onlyView){
  var sel='a[href],button,input,select,textarea,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=option],[onclick],[contenteditable=true]';
  var els=document.querySelectorAll(sel); var out=[]; window.__cbEls=[];
  var vh=window.innerHeight;
  for(var i=0;i<els.length&&out.length<150;i++){
    var e=els[i]; var r=e.getBoundingClientRect();
    if(r.width<2||r.height<2) continue;
    var st=getComputedStyle(e);
    if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0') continue;
    if(e.type==='hidden') continue;
    var inView=r.bottom>0&&r.top<vh;
    if(onlyView&&!inView) continue;
    var t;
    if(e.type==='password'){ t='(password field)'; }
    else {
      t=(e.getAttribute('aria-label')||e.innerText||e.value||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||'');
      if(!t){ var im=e.querySelector&&e.querySelector('img[alt]'); if(im) t=im.getAttribute('alt')||''; }
      t=String(t).replace(/\s+/g,' ').trim().slice(0,80);
    }
    var idx=window.__cbEls.length; window.__cbEls.push(e);
    out.push({i:idx,tag:e.tagName.toLowerCase(),type:(e.getAttribute('type')||''),text:t,href:(e.getAttribute('href')||'').slice(0,100),ph:(e.getAttribute('placeholder')||'').slice(0,40),inView:inView});
  }
  return JSON.stringify({items:out});
})(__ONLY__)
"""

private val LOCATE_JS = """
(function(i){
  var e=(window.__cbEls||[])[i];
  if(!e) return JSON.stringify({err:'No element '+i+'. Call find_elements first.'});
  if(!document.contains(e)) return JSON.stringify({err:'That element is gone (the page changed). Call find_elements again.'});
  try{ e.scrollIntoView({block:'center',inline:'center',behavior:'instant'}); }catch(x){ e.scrollIntoView(); }
  var r=e.getBoundingClientRect(); var vv=window.visualViewport;
  var ox=vv?vv.offsetLeft:0, oy=vv?vv.offsetTop:0, w=vv?vv.width:window.innerWidth;
  return JSON.stringify({x:r.left+r.width/2-ox,y:r.top+r.height/2-oy,vw:w,ok:(r.width>0&&r.height>0)});
})(__I__)
"""

private val TYPE_JS = """
(function(t,clear){
  var e=document.activeElement;
  while(e&&e.shadowRoot&&e.shadowRoot.activeElement) e=e.shadowRoot.activeElement;
  if(!e||e===document.body||e===document.documentElement) return JSON.stringify({err:'No text field is focused. Pass the index of a field from find_elements.'});
  if(clear){ if(typeof e.select==='function') e.select(); else document.execCommand('selectAll',false,null); }
  var ok=false;
  try{ ok=document.execCommand('insertText',false,t); }catch(x){}
  if(!ok&&('value' in e)){
    var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');
    var nv=clear?t:(String(e.value||'')+t);
    if(d&&d.set) d.set.call(e,nv); else e.value=nv;
    e.dispatchEvent(new Event('input',{bubbles:true}));
    e.dispatchEvent(new Event('change',{bubbles:true}));
    ok=true;
  }
  var v=(e.type==='password')?'(hidden)':String(('value' in e)?e.value:e.innerText).slice(0,200);
  return JSON.stringify({ok:ok,tag:e.tagName.toLowerCase(),value:v});
})(__T__,__C__)
"""

private val DISMISS_JS = """
(function(){
  var clicked=0, removed=0;
  var texts=['only allow essential cookies','decline optional cookies','reject all','reject optional cookies','afvis alle','afvis valgfrie cookies','tillad kun nødvendige cookies','tillad kun vigtige cookies','kun nødvendige','only necessary','necessary only','use necessary cookies only','refuse all','decline all','reject all cookies','only essential cookies'];
  var els=document.querySelectorAll('button,[role=button],a,span,div');
  for(var i=0;i<els.length&&clicked<1;i++){
    var t=(els[i].innerText||'').trim().toLowerCase();
    if(t.length<60&&texts.indexOf(t)>=0){ var b=els[i].closest('button,[role=button]')||els[i]; b.click(); clicked++; }
  }
  var nag=/get the full experience|log in to continue|sign up to see|log ind for at|continue in app|open in app|see more on facebook/i;
  var all=document.querySelectorAll('div,section,aside');
  for(var j=0;j<all.length&&removed<5;j++){
    var e=all[j]; if(!e.isConnected) continue;
    var st=getComputedStyle(e); if(st.position!=='fixed'&&st.position!=='sticky') continue;
    var r=e.getBoundingClientRect(); if(r.height<window.innerHeight*0.2) continue;
    var tx=e.innerText||''; if(tx.length>600||!nag.test(tx)) continue;
    e.remove(); removed++;
  }
  if(removed){
    document.querySelectorAll('div').forEach(function(e){
      var st=getComputedStyle(e); if(st.position!=='fixed') return;
      var r=e.getBoundingClientRect();
      if(r.width>=window.innerWidth*0.9&&r.height>=window.innerHeight*0.8&&!(e.innerText||'').trim()) e.remove();
    });
    document.body.style.overflow='auto'; document.documentElement.style.overflow='auto';
  }
  return JSON.stringify({clicked:clicked,removed:removed});
})()
"""

private val SEE_MORE_JS = """
(function(){
  var w=['see more','se mere','vis mere','show more','read more','læs mere','…see more','... see more','… see more'];
  var n=0;
  document.querySelectorAll('[role=button],div,span,a').forEach(function(e){
    if(n>=25) return;
    var t=(e.innerText||'').trim().toLowerCase();
    if(w.indexOf(t)<0) return;
    for(var k=0;k<e.children.length;k++){ if((e.children[k].innerText||'').trim().toLowerCase()===t) return; }
    if(e.tagName==='A'){ var h=e.getAttribute('href')||''; if(h&&h.charAt(0)!=='#'&&h.indexOf('javascript')!==0) return; }
    try{ e.click(); n++; }catch(x){}
  });
  return JSON.stringify({n:n});
})()
"""

private val FIND_TEXT_JS = """
(function(q){
  q=q.toLowerCase(); var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT); var out=[]; var first=null; var seen={}; var n;
  while((n=w.nextNode())&&out.length<20){
    var t=n.nodeValue||''; if(t.toLowerCase().indexOf(q)<0) continue;
    var p=n.parentElement; if(!p||!p.getClientRects().length) continue;
    var ctx=(p.innerText||t).replace(/\s+/g,' ').trim(); var k=ctx.toLowerCase().indexOf(q); if(k<0){ctx=t;k=t.toLowerCase().indexOf(q);}
    ctx=ctx.slice(Math.max(0,k-150),k+q.length+150);
    if(seen[ctx]) continue; seen[ctx]=1; out.push(ctx); if(!first) first=p;
  }
  if(first){ try{ first.scrollIntoView({block:'center',behavior:'instant'}); }catch(x){ first.scrollIntoView(); } }
  return JSON.stringify({count:out.length,matches:out});
})(__Q__)
"""

private val IMAGES_JS = """
(function(minSize){
  var out=[]; var seen={};
  document.querySelectorAll('img').forEach(function(i){
    var src=i.currentSrc||i.src||''; if(!src||seen[src]) return;
    if(src.indexOf('data:')!==0&&src.indexOf('http')!==0) return;
    var r=i.getBoundingClientRect(); var w=Math.max(i.naturalWidth,r.width), h=Math.max(i.naturalHeight,r.height);
    if(w<minSize||h<minSize) return;
    if(r.width<60||r.height<60) return;
    seen[src]=1; out.push({src:src,alt:(i.alt||'').slice(0,200),w:i.naturalWidth,h:i.naturalHeight,y:r.top+window.scrollY});
  });
  return JSON.stringify({items:out,page:location.href});
})(__MIN__)
"""

private val VIDEO_JS = """
(function(){
  var vs=Array.prototype.slice.call(document.querySelectorAll('video'));
  vs.sort(function(a,b){return b.clientWidth*b.clientHeight-a.clientWidth*a.clientHeight;});
  var v=vs[0]; if(!v) return JSON.stringify({err:'No video on this page.'});
  window.__cbV=v;
  try{ v.scrollIntoView({block:'center',behavior:'instant'}); }catch(x){ v.scrollIntoView(); }
  v.muted=true; try{ var p=v.play(); if(p&&p.catch) p.catch(function(){}); }catch(x){}
  var r=v.getBoundingClientRect(); var vv=window.visualViewport;
  return JSON.stringify({d:isFinite(v.duration)?v.duration:-1,ready:v.readyState,x:r.left-(vv?vv.offsetLeft:0),y:r.top-(vv?vv.offsetTop:0),w:r.width,h:r.height,vw:vv?vv.width:window.innerWidth});
})()
"""

private val TRANSCRIPT_JS = """
(async function(lang){
  function grab(html){
    var k=html.indexOf('"captionTracks":'); if(k<0) return null;
    var s=html.indexOf('[',k), d=0;
    for(var i=s;i<html.length;i++){ var c=html.charAt(i); if(c==='[') d++; else if(c===']'){ d--; if(d===0) return JSON.parse(html.slice(s,i+1)); } }
    return null;
  }
  var tracks=null;
  try{ var pr=window.ytInitialPlayerResponse; tracks=pr.captions.playerCaptionsTracklistRenderer.captionTracks; }catch(x){}
  if(!tracks){ var html=await (await fetch(location.href,{credentials:'include'})).text(); tracks=grab(html); }
  if(!tracks||!tracks.length) return {err:'No subtitles found for this video.'};
  var tr=null;
  if(lang){ tr=tracks.find(function(t){return t.languageCode===lang;}); }
  if(!tr) tr=tracks.find(function(t){return t.kind!=='asr';})||tracks[0];
  var res=await fetch(tr.baseUrl+'&fmt=json3',{credentials:'include'}); var txt=await res.text();
  if(!txt) return {err:'YouTube sent back empty subtitles for this video.'};
  var j=JSON.parse(txt); var out=[];
  (j.events||[]).forEach(function(e){
    if(!e.segs) return;
    var s=e.segs.map(function(x){return x.utf8;}).join('').replace(/\n/g,' ').trim(); if(!s) return;
    var sec=Math.floor((e.tStartMs||0)/1000);
    out.push('['+Math.floor(sec/60)+':'+String(sec%60).padStart(2,'0')+'] '+s);
  });
  var nm=''; try{ nm=tr.name.simpleText||tr.name.runs[0].text; }catch(x){}
  return {lang:tr.languageCode,name:nm,langs:tracks.map(function(t){return t.languageCode;}).join(', '),text:out.join('\n')};
})(__LANG__)
"""

private val MUT_START_JS = """
(function(){ try{ if(window.__cbObs) window.__cbObs.disconnect(); }catch(x){}
  window.__cbMut=0; window.__cbHref=location.href;
  window.__cbObs=new MutationObserver(function(m){ window.__cbMut+=m.length; });
  window.__cbObs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
  return JSON.stringify({ok:1}); })()
"""

private val MUT_CHECK_JS = """
(function(){ return JSON.stringify({m:(typeof window.__cbMut==='number')?window.__cbMut:-1, same:window.__cbHref===location.href}); })()
"""

/** Lines of site clutter (mostly Facebook's logged-out prompts) dropped by get_page. */
private val CLUTTER = setOf(
    "open app", "log in", "log ind", "get the full experience",
    "see what people are loving, listing and talking about right now.",
    "join group", "see more like this from friends in the app.", "connect with friends",
    "there's more to see", "see the latest from this group in the app.", "shared with you",
    "reply", "svar", "facebook", "…", "send", "like", "comment", "share"
)

private val ICON_CHARS = Regex("[\\uE000-\\uF8FF]|[\\uDB80-\\uDBFF][\\uDC00-\\uDFFF]")

fun cleanText(raw: String): String {
    val out = StringBuilder()
    var last = ""
    var blank = 0
    for (line0 in raw.split('\n')) {
        val line = ICON_CHARS.replace(line0, "").trim()
        if (line.isEmpty()) {
            blank++
            if (blank == 1 && out.isNotEmpty()) out.append('\n')
            continue
        }
        if (line.lowercase() in CLUTTER) continue
        if (line == last) continue
        blank = 0
        out.append(line).append('\n')
        last = line
    }
    return out.toString().trim()
}

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** All browser actions. Called from server threads; WebView work is posted to the main thread. */
class BrowserController(private val act: MainActivity) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shotScale = 0.0

    private fun <T> ui(timeoutMs: Long = 15000, block: (CompletableFuture<T>) -> Unit): T {
        val f = CompletableFuture<T>()
        main.post {
            try {
                block(f)
            } catch (e: Throwable) {
                f.completeExceptionally(e)
            }
        }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun <T> uiRun(block: () -> T): T = ui<T> { f -> f.complete(block()) }

    private fun js(script: String): String? =
        ui<String?>(20000) { f -> act.webView.evaluateJavascript(script) { r -> f.complete(r) } }

    private fun jsObj(script: String): JSONObject {
        val raw = js(script) ?: return JSONObject().put("err", "No result from the page")
        val v = JSONTokener(raw).nextValue()
        if (v !is String) return JSONObject().put("err", "The page returned: " + raw.take(300))
        return JSONObject(v)
    }

    private fun waitForLoad(maxMs: Long) {
        val end = System.currentTimeMillis() + maxMs
        Thread.sleep(300)
        while (System.currentTimeMillis() < end && (act.loading || act.pageProgress < 100)) Thread.sleep(200)
        Thread.sleep(200)
    }

    private fun where(): String =
        uiRun { "Page: " + (act.webView.title ?: "") + "\nURL: " + (act.webView.url ?: "") }

    private fun viewSize(): IntArray = uiRun { intArrayOf(act.webView.width, act.webView.height) }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        uiRun {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            act.webView.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun nativeTap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, down)
        Thread.sleep(80)
        touch(MotionEvent.ACTION_UP, x, y, down)
    }

    private fun swipe(x: Float, y1: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        val steps = 12
        touch(MotionEvent.ACTION_DOWN, x, y1, down)
        for (k in 1..steps) {
            Thread.sleep(25)
            touch(MotionEvent.ACTION_MOVE, x, y1 + (y2 - y1) * k / steps, down)
        }
        Thread.sleep(150) // short pause so it doesn't fling
        touch(MotionEvent.ACTION_UP, x, y2, down)
    }

    fun openUrl(url: String, read: Boolean): String {
        val u = normalizeUrl(url)
        uiRun {
            act.loading = true
            act.webView.loadUrl(u)
        }
        waitForLoad(20000)
        if (dismissPopups()) waitForLoad(10000)
        return if (read) "Opened.\n" + getPage(0, 4000, false) else "Opened.\n" + where()
    }

    /** Clicks "reject optional cookies" style buttons and removes "log in / open app" sheets. */
    fun dismissPopups(): Boolean {
        val r = jsObj(DISMISS_JS)
        val clicked = r.optInt("clicked", 0)
        if (clicked > 0) Thread.sleep(800)
        return clicked > 0
    }

    fun getPage(offset: Int, max: Int, full: Boolean): String {
        if (!full) {
            dismissPopups()
            val n = jsObj(SEE_MORE_JS).optInt("n", 0)
            if (n > 0) Thread.sleep(800)
        }
        val r = jsObj(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return JSON.stringify({url:location.href,title:document.title,text:t.substr(0,300000)});})()"
        )
        if (r.has("err")) return r.getString("err")
        val all = if (full) r.getString("text") else cleanText(r.getString("text"))
        val len = all.length
        val start = offset.coerceIn(0, len)
        val end = minOf(len, start + max)
        val text = all.substring(start, end)
        val more = if (end < len) "\n\n[More text: call get_page with offset=$end]" else ""
        return "Title: " + r.optString("title") + "\nURL: " + r.optString("url") +
            "\nText $start-$end of $len characters:\n\n" + text + more
    }

    fun findElements(onlyView: Boolean): String {
        val r = jsObj(FIND_JS.replace("__ONLY__", onlyView.toString()))
        if (r.has("err")) return r.getString("err")
        val items = r.getJSONArray("items")
        if (items.length() == 0) return "No buttons, links or fields found.\n" + where()
        val sb = StringBuilder(where()).append("\n\nElements (use the number with click_element / type_text):\n")
        for (k in 0 until items.length()) {
            val o = items.getJSONObject(k)
            sb.append('[').append(o.getInt("i")).append("] ").append(o.getString("tag"))
            val type = o.optString("type")
            if (type.isNotEmpty()) sb.append('(').append(type).append(')')
            sb.append(" \"").append(o.optString("text")).append('"')
            val ph = o.optString("ph")
            if (ph.isNotEmpty() && ph != o.optString("text")) sb.append(" placeholder=\"").append(ph).append('"')
            val href = o.optString("href")
            if (href.isNotEmpty()) sb.append(" -> ").append(href)
            if (!o.optBoolean("inView")) sb.append(" (off-screen)")
            sb.append('\n')
        }
        return sb.toString()
    }

    fun clickElement(i: Int): String {
        val r = jsObj(LOCATE_JS.replace("__I__", i.toString()))
        if (r.has("err")) return r.getString("err")
        Thread.sleep(150)
        val size = viewSize()
        val ratio = size[0] / r.getDouble("vw")
        val x = r.getDouble("x") * ratio
        val y = r.getDouble("y") * ratio
        val jsClick = "(function(){var e=window.__cbEls[$i];if(e.focus)e.focus();e.click();return JSON.stringify({ok:1});})()"
        var how = "tap"
        if (r.optBoolean("ok") && x >= 0 && x <= size[0] && y >= 0 && y <= size[1]) {
            jsObj(MUT_START_JS)
            nativeTap(x.toFloat(), y.toFloat())
            Thread.sleep(700)
            val c = jsObj(MUT_CHECK_JS)
            // Nothing happened: no page change, no DOM change and nothing loading. Click it with a script instead.
            if (c.optInt("m", -1) == 0 && c.optBoolean("same", false) && !act.loading) {
                jsObj(jsClick)
                how = "tap had no effect, used script click"
            }
        } else {
            jsObj(jsClick)
            how = "script click"
        }
        Thread.sleep(500)
        waitForLoad(10000)
        return "Clicked [$i] ($how).\n" + where()
    }

    fun typeText(text: String, index: Int?, clear: Boolean, submit: Boolean): String {
        if (index != null) {
            val c = clickElement(index)
            if (!c.startsWith("Clicked")) return c
        }
        val script = TYPE_JS.replace("__C__", clear.toString()).replace("__T__", JSONObject.quote(text))
        val r = jsObj(script)
        if (r.has("err")) return r.getString("err")
        var res = "Typed into " + r.optString("tag") + ". The field now has: \"" + r.optString("value") + "\""
        if (submit) res += "\n" + pressKey("enter")
        return res
    }

    fun pressKey(key: String): String {
        val code = when (key.trim().lowercase()) {
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "backspace", "delete" -> KeyEvent.KEYCODE_DEL
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "space" -> KeyEvent.KEYCODE_SPACE
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return "Unknown key '$key'. Use enter, tab, backspace, escape, space, up, down, left or right."
        }
        uiRun {
            act.webView.requestFocus()
            act.webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            act.webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        Thread.sleep(500)
        waitForLoad(10000)
        return "Pressed $key.\n" + where()
    }

    fun scroll(down: Boolean, amount: Double): String {
        val sign = if (down) 1 else -1
        val dy = "$sign*window.innerHeight*0.8*$amount"
        val r = jsObj(
            "(function(){var y0=window.scrollY;" +
                "try{window.scrollBy({top:$dy,behavior:'instant'});}catch(x){window.scrollBy(0,$dy);}" +
                "return JSON.stringify({y0:y0,y1:window.scrollY});})()"
        )
        if (r.has("err")) return r.getString("err")
        var how = "page"
        if (r.optDouble("y0") == r.optDouble("y1")) {
            // Page itself didn't move (inner scroll area or end of page): use a finger swipe instead.
            val size = viewSize()
            val x = size[0] / 2f
            val reps = maxOf(1, amount.roundToInt())
            for (k in 0 until reps) {
                if (down) swipe(x, size[1] * 0.75f, size[1] * 0.25f) else swipe(x, size[1] * 0.25f, size[1] * 0.75f)
                Thread.sleep(300)
            }
            how = "swipe"
        }
        Thread.sleep(300)
        return "Scrolled " + (if (down) "down" else "up") + " ($how).\n" + where()
    }

    private fun drawInto(b: Bitmap) {
        val c = Canvas(b)
        c.translate(-act.webView.scrollX.toFloat(), -act.webView.scrollY.toFloat())
        act.webView.draw(c)
    }

    private fun captureBitmap(): Bitmap {
        return ui<Bitmap>(10000) { f ->
            val wv = act.webView
            val w = wv.width
            val h = wv.height
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            wv.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    // Copies from whichever window the browser is in (the app or the background overlay).
                    val req = PixelCopy.Request.Builder.ofWindow(wv)
                        .setSourceRect(rect)
                        .setDestinationBitmap(b)
                        .build()
                    PixelCopy.request(req, { r -> main.post(r) }, { result ->
                        if (result.status != PixelCopy.SUCCESS) drawInto(b)
                        f.complete(b)
                    })
                } else if (act.inOverlay) {
                    drawInto(b)
                    f.complete(b)
                } else {
                    PixelCopy.request(act.window, rect, b, { res ->
                        if (res != PixelCopy.SUCCESS) drawInto(b)
                        f.complete(b)
                    }, main)
                }
            } catch (e: Exception) {
                drawInto(b)
                f.complete(b)
            }
        }
    }

    private fun toJpegBase64(src: Bitmap, maxSide: Int, quality: Int): Triple<String, Int, Int> {
        val sc = min(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        val out = if (sc < 1.0) {
            Bitmap.createScaledBitmap(src, maxOf(1, (src.width * sc).toInt()), maxOf(1, (src.height * sc).toInt()), true)
        } else {
            src
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Triple(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), out.width, out.height)
    }

    fun screenshot(): Pair<String, String> {
        val bmp = captureBitmap()
        val scale = min(1.0, 720.0 / bmp.width)
        shotScale = scale
        val (data, w, h) = toJpegBase64(bmp, maxOf((bmp.width * scale).toInt(), (bmp.height * scale).toInt()), 70)
        val info = "Screenshot is ${w}x${h} px.\n" + where() +
            "\nTo tap something here, call tap with x,y in this screenshot's pixels."
        return Pair(data, info)
    }

    /** Downloads the page's pictures (with the browser's cookies) and returns them as small JPEGs. */
    fun getImages(max: Int, minSize: Int): Pair<String, List<String>> {
        val r = jsObj(IMAGES_JS.replace("__MIN__", minSize.toString()))
        if (r.has("err")) return Pair(r.getString("err"), emptyList())
        val items = r.getJSONArray("items")
        val page = r.optString("page")
        val ua = uiRun { act.webView.settings.userAgentString }
        val cm = CookieManager.getInstance()
        val images = ArrayList<String>()
        val sb = StringBuilder("Found ${items.length()} pictures on the page")
        if (items.length() > max) sb.append(", showing the first $max")
        sb.append(".\n")
        var k = 0
        while (k < items.length() && images.size < max) {
            val o = items.getJSONObject(k)
            k++
            val src = o.getString("src")
            try {
                val bytes: ByteArray = if (src.startsWith("data:")) {
                    Base64.decode(src.substringAfter(","), Base64.DEFAULT)
                } else {
                    val c = URL(src).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", ua)
                    c.setRequestProperty("Referer", page)
                    cm.getCookie(src)?.let { c.setRequestProperty("Cookie", it) }
                    c.inputStream.use { it.readBytes() }
                }
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 768) sample *= 2
                val dec = BitmapFactory.Options()
                dec.inSampleSize = sample
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dec) ?: continue
                val (data, w, h) = toJpegBase64(bmp, 768, 72)
                images.add(data)
                val alt = o.optString("alt")
                sb.append("Picture ${images.size}: ${w}x${h}")
                if (alt.isNotEmpty()) sb.append(" - Facebook/page description: \"").append(alt).append('"')
                sb.append('\n')
            } catch (e: Exception) {
                sb.append("(skipped one picture: ${e.message})\n")
            }
        }
        if (images.isEmpty()) sb.append("No pictures could be loaded.\n")
        return Pair(sb.toString(), images)
    }

    /** Takes frames spread over the page's main video, cropped to the video. */
    fun videoFrames(count: Int): Pair<String, List<String>> {
        var info = jsObj(VIDEO_JS)
        if (info.has("err")) return Pair(info.getString("err"), emptyList())
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && (info.optDouble("d", -1.0) <= 0 || info.optInt("ready", 0) < 1)) {
            Thread.sleep(500)
            info = jsObj(VIDEO_JS)
        }
        val d = info.optDouble("d", -1.0)
        val frames = ArrayList<String>()
        val sb = StringBuilder()
        sb.append(if (d > 0) "Video is ${d.roundToInt()} s long. " else "Video length unknown (live or still loading). ")
        sb.append("Frames:\n")
        for (i in 0 until count) {
            val t: Double
            if (d > 0) {
                t = d * (i + 0.5) / count
                jsObj("(function(){var v=window.__cbV;v.pause();v.currentTime=$t;return JSON.stringify({ok:1});})()")
                val until = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < until) {
                    val st = jsObj("(function(){var v=window.__cbV;return JSON.stringify({s:v.seeking,r:v.readyState});})()")
                    if (!st.optBoolean("s", true) && st.optInt("r", 0) >= 2) break
                    Thread.sleep(250)
                }
                Thread.sleep(400)
            } else {
                if (i > 0) Thread.sleep(2000)
                t = jsObj("(function(){return JSON.stringify({t:window.__cbV.currentTime});})()").optDouble("t", 0.0)
            }
            val pos = jsObj(VIDEO_JS)
            val bmp = captureBitmap()
            val ratio = bmp.width / pos.optDouble("vw", bmp.width.toDouble())
            val x = (pos.optDouble("x", 0.0) * ratio).toInt().coerceIn(0, bmp.width - 1)
            val y = (pos.optDouble("y", 0.0) * ratio).toInt().coerceIn(0, bmp.height - 1)
            val w = (pos.optDouble("w", 0.0) * ratio).toInt().coerceIn(1, bmp.width - x)
            val h = (pos.optDouble("h", 0.0) * ratio).toInt().coerceIn(1, bmp.height - y)
            val crop = if (w > 50 && h > 50) Bitmap.createBitmap(bmp, x, y, w, h) else bmp
            frames.add(toJpegBase64(crop, 640, 70).first)
            val sec = t.roundToInt()
            sb.append("Frame ${i + 1}: at ${sec / 60}:${String.format("%02d", sec % 60)}\n")
        }
        jsObj("(function(){try{window.__cbV.pause();}catch(x){}return JSON.stringify({ok:1});})()")
        return Pair(sb.toString() + where(), frames)
    }

    /** Runs an async JS function and waits for its result (evaluateJavascript can't await promises). */
    private fun jsAsync(asyncCall: String, timeoutMs: Long): JSONObject {
        js(
            "window.__cbAsync=null;(" + asyncCall + ").then(function(r){window.__cbAsync=JSON.stringify({ok:true,r:r});})" +
                ".catch(function(e){window.__cbAsync=JSON.stringify({ok:false,err:String(e)});});1"
        )
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            Thread.sleep(300)
            val raw = js("window.__cbAsync") ?: continue
            val v = JSONTokener(raw).nextValue()
            if (v is String) return JSONObject(v)
        }
        return JSONObject().put("ok", false).put("err", "timed out")
    }

    fun getTranscript(lang: String): String {
        val r = jsAsync(TRANSCRIPT_JS.replace("__LANG__", JSONObject.quote(lang)), 20000)
        if (!r.optBoolean("ok")) return "Couldn't get subtitles: " + r.optString("err")
        val o = r.getJSONObject("r")
        if (o.has("err")) return o.getString("err")
        val text = o.optString("text")
        return "Subtitles (" + o.optString("lang") + (if (o.optString("name").isNotEmpty()) ", " + o.optString("name") else "") +
            "). Available languages: " + o.optString("langs") + "\n\n" + text.take(40000) +
            (if (text.length > 40000) "\n[cut off]" else "")
    }

    fun findText(query: String): String {
        val r = jsObj(FIND_TEXT_JS.replace("__Q__", JSONObject.quote(query)))
        if (r.has("err")) return r.getString("err")
        val m = r.getJSONArray("matches")
        if (m.length() == 0) return "\"$query\" was not found on this page."
        val sb = StringBuilder("Found ${m.length()} match(es) for \"$query\" (scrolled to the first):\n")
        for (k in 0 until m.length()) sb.append("${k + 1}. …").append(m.getString(k)).append("…\n")
        return sb.toString()
    }

    fun setView(desktop: Boolean): String {
        uiRun {
            act.webView.settings.userAgentString = if (desktop) DESKTOP_UA else null
            act.loading = true
            act.webView.reload()
        }
        waitForLoad(20000)
        return (if (desktop) "Switched to the desktop version." else "Switched to the phone version.") + "\n" + where()
    }

    // ---- idle handling (battery) ----
    @Volatile private var frozen = false
    @Volatile private var unloadedUrl: String? = null

    /** Called by the idle checker: freeze the page after 1 min idle, unload it after 15 min (only while in the background). */
    fun idleTick() {
        val idle = System.currentTimeMillis() - AppState.lastRequest
        if (!act.inOverlay) return
        if (idle > 15 * 60_000L && unloadedUrl == null) {
            main.post {
                val url = act.webView.url
                if (url != null && url != "about:blank") {
                    act.webView.onResume()
                    act.webView.resumeTimers()
                    unloadedUrl = url
                    act.webView.loadUrl("about:blank")
                    act.webView.onPause()
                    act.webView.pauseTimers()
                    frozen = true
                }
            }
        } else if (idle > 60_000L && !frozen) {
            main.post {
                act.webView.onPause()
                act.webView.pauseTimers()
                frozen = true
            }
        }
    }

    /** Wake the page up before Claude uses it. reloadIfUnloaded=false for open_url (it loads a new page anyway). */
    fun wake(reloadIfUnloaded: Boolean) {
        if (frozen) {
            uiRun {
                act.webView.onResume()
                act.webView.resumeTimers()
            }
            frozen = false
        }
        val u = unloadedUrl
        if (u != null) {
            unloadedUrl = null
            if (reloadIfUnloaded) {
                uiRun {
                    act.loading = true
                    act.webView.loadUrl(u)
                }
                waitForLoad(20000)
            }
        }
    }

    fun tap(x: Double, y: Double): String {
        val size = viewSize()
        val scale = if (shotScale > 0) shotScale else min(1.0, 720.0 / size[0])
        nativeTap((x / scale).toFloat(), (y / scale).toFloat())
        Thread.sleep(600)
        waitForLoad(10000)
        return "Tapped at ($x, $y).\n" + where()
    }

    fun goBack(): String {
        val could = uiRun {
            if (act.webView.canGoBack()) {
                act.webView.goBack()
                true
            } else {
                false
            }
        }
        if (!could) return "There is no previous page."
        Thread.sleep(400)
        waitForLoad(10000)
        return "Went back.\n" + where()
    }

    fun runJs(code: String): String {
        val wrapped = "(function(){try{var __r=(function(){\n" + code + "\n})();var s;" +
            "try{s=JSON.stringify(__r);}catch(x){s=String(__r);}" +
            "return JSON.stringify({ok:true,r:(s===undefined?'undefined':s)});}" +
            "catch(e){return JSON.stringify({ok:false,err:String(e)});}})()"
        val r = jsObj(wrapped)
        if (r.has("err")) return "Error: " + r.getString("err")
        return r.optString("r").take(20000)
    }

    fun waitSeconds(s: Double): String {
        val ms = (if (s.isNaN()) 2.0 else s.coerceIn(0.0, 30.0)) * 1000
        Thread.sleep(ms.toLong())
        return "Waited.\n" + where()
    }
}
