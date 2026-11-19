package com.remotebrowser.app

import android.graphics.Bitmap
import android.graphics.Canvas
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

    fun openUrl(url: String): String {
        val u = normalizeUrl(url)
        uiRun {
            act.loading = true
            act.webView.loadUrl(u)
        }
        waitForLoad(20000)
        return "Opened.\n" + where()
    }

    fun getPage(offset: Int, max: Int): String {
        val r = jsObj(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return JSON.stringify({url:location.href,title:document.title,len:t.length,text:t.substr($offset,$max)});})()"
        )
        if (r.has("err")) return r.getString("err")
        val len = r.getInt("len")
        val text = r.getString("text")
        val end = offset + text.length
        val more = if (end < len) "\n\n[More text: call get_page with offset=$end]" else ""
        return "Title: " + r.optString("title") + "\nURL: " + r.optString("url") +
            "\nText $offset-$end of $len characters:\n\n" + text + more
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
        if (r.optBoolean("ok") && x >= 0 && x <= size[0] && y >= 0 && y <= size[1]) {
            nativeTap(x.toFloat(), y.toFloat())
        } else {
            js("(function(){var e=window.__cbEls[$i];if(e.focus)e.focus();e.click();})()")
        }
        Thread.sleep(600)
        waitForLoad(10000)
        return "Clicked [$i].\n" + where()
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

    fun screenshot(): Pair<String, String> {
        val bmp = ui<Bitmap>(10000) { f ->
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
        val scale = min(1.0, 720.0 / bmp.width)
        shotScale = scale
        val out = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        val data = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        val info = "Screenshot is ${out.width}x${out.height} px.\n" + where() +
            "\nTo tap something here, call tap with x,y in this screenshot's pixels."
        return Pair(data, info)
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
