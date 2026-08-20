package com.proxybrowser.app.core

import android.content.Context

object VideoSniffer {
    const val HOOK_JS = """
(function(){
  var orig = window.open;
  window.open = function(u,t){
    try{ window.PB.report(JSON.stringify({url:u,type:'video'})); }catch(e){}
    return orig.apply(this,arguments);
  };
  var href = Object.getOwnPropertyDescriptor(window.HTMLAnchorElement.prototype,'href');
  Object.defineProperty(window.HTMLAnchorElement.prototype,'href',{
    get:function(){return href.get.call(this);},
    set:function(u){
      if(u&&(/\\.(mp4|webm|mkv|avi)/i.test(u)||u.indexOf('video')>=0)){
        try{ window.PB.report(JSON.stringify({url:u,type:'video'})); }catch(e){}
      }
      href.set.call(this,u);
    }
  });
})();
""".trimIndent()

    data class Media(val url: String, val type: String, val pageUrl: String, val title: String, val size: Long, val ext: String)

    private val items = mutableListOf<Media>()

    fun load(ctx: Context) {}

    fun add(ctx: Context, media: Media) {
        items.add(media)
    }

    fun getAll(): List<Media> = items.toList()
}
