package jp.handwriting.note.editor

import android.graphics.*
import android.os.SystemClock
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class StrokeTileCacheTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private fun stroke(y: Float, color: Int = 0x66123456, x: Float = 100f): EditorStroke {
        val inputs = MutableStrokeInputBatch()
        repeat(33) { i -> inputs.add(InputToolType.STYLUS, x+i*10f, y+12f*kotlin.math.sin(i*.2f), i*8L) }
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(),color,12f,.001f),inputs))
    }
    private inner class Scene(val budget: Long = 80L*1024*1024) {
        val cache = StrokeTileCache({}, budget)
        var entries = listOf(1L to stroke(100f),2L to stroke(310f,0xff235722.toInt()))
        var position = ViewPosition(1000f/512f)
        var offset = WorldPoint(0f,0f)
        var selected: EditorStroke? = null
        var gesture = false
        var selectionFactor = 1f
        var fallbackCalls = 0
        private val renderer = InkStrokeRenderer.createForDisplay()
        fun render(cached: Boolean = true): Bitmap {
            val result = Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
            ins.runOnMainSync {
                val canvas = Canvas(result); canvas.drawColor(Color.WHITE)
                val scale=512f/Viewport.WIDTH*position.zoom
                val matrix=Matrix().apply { setValues(floatArrayOf(scale,0f,-position.left*scale,0f,scale,-position.top*scale,0f,0f,1f)) }
                fun vectors(target: Canvas) {
                    fallbackCalls++
                    entries.forEach { (_,stroke) ->
                        val saved=target.save();target.concat(matrix)
                        if (stroke===selected) target.translate(offset.x,offset.y)
                        if(stroke.image!=null) EmbeddedImages.draw(target,stroke.image.resized(if(stroke===selected) selectionFactor else 1f))
                        else renderer.draw(target,stroke.ink,matrix)
                        target.restoreToCount(saved)
                    }
                }
                if(cached) cache.draw(canvas,entries,position,512,512,offset,{it===selected},selectionFactor,gesture,::vectors)
                else vectors(canvas)
            }
            return result
        }
        fun settle() {
            val deadline=SystemClock.uptimeMillis()+10000
            while(true) {
                render().recycle()
                var done=false
                ins.runOnMainSync { done=cache.pendingCount==0 && cache.fallbackTiles==0 && !cache.needsRefinement }
                if(done) return
                check(SystemClock.uptimeMillis()<deadline) { "Tile preparation did not settle" }
                SystemClock.sleep(30)
            }
        }
        fun compare() {
            val actual=render();val reference=render(false)
            try {
                val a=IntArray(512*512);val b=IntArray(a.size)
                actual.getPixels(a,0,512,0,0,512,512);reference.getPixels(b,0,512,0,0,512,512)
                var different=0;var maxError=0
                for(i in a.indices) {
                    val error=maxOf(kotlin.math.abs(Color.red(a[i])-Color.red(b[i])),kotlin.math.abs(Color.green(a[i])-Color.green(b[i])),kotlin.math.abs(Color.blue(a[i])-Color.blue(b[i])))
                    if(error>3) different++
                    maxError=maxOf(maxError,error)
                }
                assertTrue("Raster seams, opacity or placement changed: pixels=$different maxError=$maxError", different<80)
            } finally { actual.recycle();reference.recycle() }
        }
        fun close() = ins.runOnMainSync { cache.close() }
    }
    @Test fun appendEraseMoveAndUndoKeepPixelsAndOnlyUpdateAffectedTiles() {
        val scene=Scene()
        try {
            scene.settle();scene.compare()
            val recorded=scene.cache.rasterizedTiles
            val fallback=scene.fallbackCalls
            scene.render().recycle()
            assertEquals(recorded,scene.cache.rasterizedTiles)
            assertEquals(fallback,scene.fallbackCalls)
            val original=scene.entries
            scene.entries=original+(3L to stroke(130f,0x66994433))
            scene.compare() // Fallback must also preserve opacity while replacements are pending.
            scene.settle();scene.compare()
            assertTrue(scene.cache.appendedStrokes>0)
            assertTrue("Unchanged lower tiles must remain cached",scene.cache.rasterizedTiles-recorded<=2)
            scene.entries=original;scene.settle();scene.compare()
            scene.selected=original.first().second;scene.offset=WorldPoint(280f,270f)
            scene.settle();scene.compare()
            scene.offset=WorldPoint(0f,0f);scene.selected=null
            scene.settle();scene.compare()
        } finally { scene.close() }
    }
    @Test fun gesturesReusePixelsThenRefineAndLargeCoordinatesStayAligned() {
        val scene=Scene()
        try {
            scene.settle();val before=scene.cache.rasterizedTiles
            scene.gesture=true;scene.position=ViewPosition(2000f/512f)
            scene.render().recycle()
            assertEquals("A pinch should transform existing pixels",before,scene.cache.rasterizedTiles)
            scene.gesture=false;SystemClock.sleep(180)
            scene.settle();scene.compare()
            scene.position=ViewPosition(2000f/512f,0f,200000f)
            scene.entries=listOf(1L to stroke(200100f))
            scene.settle();scene.compare()
            scene.position=ViewPosition(2000f/512f,64f,200064f)
            scene.settle();scene.compare()
        } finally { scene.close() }
    }
    @Test fun hardwareCanvasActuallyComposesPreparedTilesWithoutVectorReplay() {
        ins.uiAutomation.executeShellCommand("input keyevent 224").close()
        val ready=java.util.concurrent.CountDownLatch(1)
        val draws=java.util.concurrent.atomic.AtomicInteger()
        lateinit var view: android.view.View
        lateinit var cache: StrokeTileCache
        val entries=listOf(1L to stroke(100f),2L to stroke(310f))
        val renderer=InkStrokeRenderer.createForDisplay()
        androidx.test.core.app.ActivityScenario.launch(jp.handwriting.note.ProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true);activity.setTurnScreenOn(true)
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                cache=StrokeTileCache({view.postInvalidateOnAnimation()})
                view=object: android.view.View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        draws.incrementAndGet()
                        assertTrue(canvas.isHardwareAccelerated)
                        canvas.drawColor(Color.WHITE)
                        cache.draw(canvas,entries,ViewPosition(1000f/512f),512,512,WorldPoint(0f,0f),{false},1f,false) { target ->
                            entries.forEach { renderer.draw(target,it.second.ink,Matrix()) }
                        }
                        if(cache.fallbackTiles==0 && cache.pendingCount==0) ready.countDown()
                    }
                }
                val root=android.widget.FrameLayout(activity)
                root.addView(view,android.widget.FrameLayout.LayoutParams(512,512).apply { leftMargin=400;topMargin=400 })
                activity.setContentView(root)
            }
            try {
                assertTrue("GPU tiles never became ready (draws=${draws.get()})",ready.await(10,java.util.concurrent.TimeUnit.SECONDS))
                SystemClock.sleep(200)
                val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                val copied=java.util.concurrent.CountDownLatch(1);var code=-1
                scenario.onActivity { activity ->
                    val location=IntArray(2);view.getLocationInWindow(location)
                    android.view.PixelCopy.request(activity.window,Rect(location[0],location[1],location[0]+512,location[1]+512),bitmap,
                        { code=it;copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper()))
                }
                assertTrue(copied.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,code)
                val expected=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
                try {
                    val target=Canvas(expected);target.drawColor(Color.WHITE)
                    entries.forEach { renderer.draw(target,it.second.ink,Matrix()) }
                    val actual=IntArray(512*512);val reference=IntArray(actual.size)
                    bitmap.getPixels(actual,0,512,0,0,512,512);expected.getPixels(reference,0,512,0,0,512,512)
                    val mismatches=actual.indices.count { i ->
                        maxOf(kotlin.math.abs(Color.red(actual[i])-Color.red(reference[i])),kotlin.math.abs(Color.green(actual[i])-Color.green(reference[i])),kotlin.math.abs(Color.blue(actual[i])-Color.blue(reference[i])))>3
                    }
                    assertTrue("Hardware tile composition differs: $mismatches pixels",mismatches<80)
                } finally { bitmap.recycle();expected.recycle() }
            } finally { ins.runOnMainSync { cache.close() } }
        }
    }
    @Test fun imagesPreserveLayerOrderAndSelectionResize() {
        val scene=Scene()
        val image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        image.eraseColor(0xAA3366CC.toInt())
        val encoded=java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        image.recycle()
        val embedded=EditorStroke(jp.handwriting.note.storage.ImageRecord(jp.handwriting.note.storage.ImageData(encoded),220f,80f,100f,100f))
        try {
            scene.entries=listOf(1L to stroke(100f),2L to embedded,3L to stroke(130f,0x66773311))
            scene.settle();scene.compare()
            scene.selected=embedded;scene.offset=WorldPoint(140f,120f);scene.selectionFactor=1.7f
            scene.settle();scene.compare()
        } finally { scene.close() }
    }
    @Test fun memoryIsBoundedAndObsoleteWorkCannotRestoreErasedInk() {
        val scene=Scene(8L*1024*1024)
        try {
            scene.entries=(1L..300L).map { it to stroke(it.toFloat()*2) }
            scene.render().recycle()
            scene.entries=emptyList()
            scene.settle();scene.compare()
            SystemClock.sleep(200)
            scene.render().recycle()
            assertEquals(0,scene.cache.pendingCount)
            assertTrue(scene.cache.bytes<=scene.budget)
            scene.entries=(0 until 10).flatMap { i -> listOf((i*2+1L) to stroke(i*512f+100f),(i*2+2L) to stroke(i*512f+310f)) }
            for(i in 0 until 10) {
                scene.position=ViewPosition(1000f/512f,0f,i*512f)
                scene.settle()
                assertTrue(scene.cache.bytes<=scene.budget)
            }
            assertTrue("Exercise more tile allocations than the memory budget holds",scene.cache.rasterizedTiles>32)
            scene.position=ViewPosition(1000f/512f)
            scene.settle();scene.compare()
        } finally { scene.close() }
    }
}
