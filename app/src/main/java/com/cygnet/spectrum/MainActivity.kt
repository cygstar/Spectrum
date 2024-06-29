package com.cygnet.spectrum

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cygnet.spectrum.databinding.ActivityHeadBinding
import com.cygnet.spectrum.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.un4seen.bass.BASS
import java.io.File
import java.lang.System.arraycopy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val FILE_PERMISSION_REQUEST_CODE = 1001
        private const val DOUBLE_CLICK_TIME_DELTA = 300L
        private const val LONG_PRESS_TIME_DELTA = 1000L
    }

    private lateinit var bindMain: ActivityMainBinding
    private lateinit var bindHead: ActivityHeadBinding
    private lateinit var openSettingsLauncher: ActivityResultLauncher<Intent>

    private val fDate = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    private val fDay = SimpleDateFormat("cccc", Locale.getDefault())

    private val libs = listOf("libbassalac.so", "libbassape.so", "libbassflac.so", "libbassopus.so", "libbasswebm.so", "libbass_aac.so", "libbass_ac3.so")
    private var fileList: Array<String> = emptyArray()
    private val dirRoot = Environment.getExternalStorageDirectory()
    private var filePath = File(dirRoot, Environment.DIRECTORY_DOWNLOADS)
    private var fileName = ""

    // for bass library
    private var hdlSync = 0  // sync handle
    private var hdlStream: Int = 0 // channel handle

    // audio spectrum
    // spectrum mode
    // 0 = "normal" FFT
    // 1 = logarithmic, combine bins, fast
    // 2 = logarithmic, combine bins, slowly falling
    // 3 = 3d
    // 4 = waveform
    // 5 = momo solid waveform
    private var specMode = 1
    private var specPos = 0  // marker position for 3D mode
    private val refreshRate = 50L // long
    private var sWidth = 1024  // bitmap size
    private var sHeight = 280
    private var dotColor = Color.WHITE
    private val iCh = BASS.BASS_CHANNELINFO()
    private var specBuf = IntArray(sWidth * sHeight)
    private var sPk: IntArray = IntArray(size = sWidth * 2, init = { 0 })  // falling peak level
    private val nPal: IntArray = IntArray(256)  // palette color for bitmap
    private lateinit var bBuf: ByteBuffer
    private lateinit var aPcm: ShortArray
    private lateinit var fft: FloatArray
    private var nBands = 128
    private var sP = 0f
    private var sY = 0
    private var sV = 0
    private var sZ = 0
    private var sB = 0
    private var sS = 0

    // for mp3 peak leve
    private lateinit var bitmap: Bitmap
    private var arrPeak: MutableList<Int> = ArrayList()

    private var canvas = Canvas()
    private var paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nImgWidth = 0f
    private var nImgHeight = 0f
    private var mp3Len = 0L
    private var level = 0
    private var len = 0.0
    private var pos = 0.0
    private var time = ""

    private var flgPlay = false
    private var flgStereo = true
    private var posStart = 0L
    private var posEnd = 0L

    // for click event
    private var upX = 0f
    private var downX = 0f
    private var nLastTap = 0L
    private var nTapDown = 0L

    private var toast: Toast? = null
    private var handler = Handler(Looper.getMainLooper())

    private val timerOneTap = Runnable { onSingleClick(upX) }
    private val timerLPress = Runnable { onLongPress(downX) }

    // audio spectrum timer
    private val timerSpectrum: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)

            if (BASS.BASS_ChannelIsActive(hdlStream) == BASS.BASS_ACTIVE_PLAYING) {

                pos = BASS.BASS_ChannelBytes2Seconds(hdlStream, BASS.BASS_ChannelGetPosition(hdlStream, BASS.BASS_POS_BYTE))
                bindMain.txtTime.text = String.format("%s ▹ %s", secToMark(pos), time)

                // current mp3 position
                bindMain.posLine.translationX = BASS.BASS_ChannelGetPosition(hdlStream, BASS.BASS_POS_BYTE).toFloat() / mp3Len * nImgWidth
                // channel peak level
                level = BASS.BASS_ChannelGetLevel(hdlStream)
                // The lower 16 bits are the peak value of the left channel
                // the upper 16 bits are the peak value of the right channel
                bindMain.barLeft.progress = ((level shr 16).toFloat() / 32768 * 100).toInt()
                bindMain.barRight.progress = ((level and 0xFFFF).toFloat() / 32768 * 100).toInt()

                when (specMode) {
                    // mono solid waveform
                    5 -> {
                        Arrays.fill(specBuf, 0)
                        BASS.BASS_ChannelGetData(hdlStream, bBuf, sWidth * 2)  // get the sample data
                        aPcm = ShortArray(sWidth)  // allocate a "short" array for the sample data
                        bBuf.asShortBuffer()[aPcm]  // get the data from the buffer into the array
                        sY = 0
                        for (sX in 0 until sWidth) {
                            sV = (32767 - aPcm[sX]) * sHeight / 65536  // invert and scale to fit display
                            if (sX == 0) sY = sV
                            do {
                                // draw line from previous sample...
                                if (sY < sV) sY++ else if (sY > sV) sY--
                                specBuf[sY * sWidth + sX] = nPal[1]
                            } while (sY != sV)
                        }
                    }
                    // waveform
                    4 -> {
                        Arrays.fill(specBuf, 0)
                        BASS.BASS_ChannelGetData(hdlStream, bBuf, iCh.chans * sWidth * 4)  // get the sample data
                        aPcm = ShortArray(iCh.chans * sWidth)  // allocate a "float" array for the sample data
                        bBuf.asShortBuffer()[aPcm]  // get the data from the buffer into the array
                        for (sC in 0 until iCh.chans) {
                            sY = 0
                            for (sX in 0 until sWidth) {
                                // must use this way to get the waveform data, short range, not float range
                                sV = (32767 - aPcm[sX * iCh.chans + sC]) * sHeight / 65536  // invert and scale to fit display
                                if (iCh.chans > 1) {
                                    sV = if (sC and 1 == 1) sV + 6 else sV - 6  // left move up, right move down
                                }
                                if (sV <= 0) sV = 0
                                if (sV >= sHeight) sV = sHeight - 1
                                if (sX == 0) sY = sV
                                do {
                                    // draw line from previous sample...
                                    if (sY < sV) sY++ else if (sY > sV) sY--
                                    // left = green, right = red (could add more colours to nPal for more channels)
                                    if (iCh.chans > 1) {
                                        specBuf[sY * sWidth + sX] = if (sC and 1 == 1) Color.parseColor("#FFCDD2") else Color.parseColor("#C5E1A5")
                                    }
                                    else {
                                        specBuf[sY * sWidth + sX] = nPal[1]
                                    }
                                } while (sY != sV)
                            }
                        }
                    }

                    else -> {
                        BASS.BASS_ChannelGetData(hdlStream, bBuf, BASS.BASS_DATA_FFT2048)  // get the FFT data
                        fft = FloatArray(sWidth)  // allocate a "float" array for the FFT data
                        bBuf.asFloatBuffer()[fft]  // get the data from the buffer into the array
                        when (specMode) {
                            // "normal" FFT
                            0 -> {
                                Arrays.fill(specBuf, 0)
                                sZ = 0
                                for (sX in 0 until sWidth / 2) {
                                    sY = (sqrt(fft[sX + 1].toDouble()) * 3 * sHeight - 4).toInt()
                                    if (sY <= 0) sY = 0 else if (sY >= sHeight) sY = sHeight - 1  // cap it
                                    if (sY > sPk[sX]) sPk[sX] = sY else sPk[sX] = sPk[sX] - 2
                                    if (sPk[sX] <= 0) sPk[sX] = 0
                                    specBuf[(sHeight - 1 - sPk[sX]) * sWidth + sX * 2] = dotColor
                                    // interpolate from previous to make the display smoother
                                    if (sX > 0) {
                                        sZ = (sY + sZ) / 2
                                        specBuf[(sHeight - 1 - (sPk[sX] + sZ) / 2) * sWidth + sX * 2 - 1] = dotColor
                                        while (--sZ >= 0) specBuf[(sHeight - 1 - sZ) * sWidth + sX * 2 - 1] = nPal[sZ * 127 / sHeight + 1]
                                    }
                                    sZ = sY
                                    // draw level
                                    while (--sY >= 0) {
                                        specBuf[(sHeight - 1 - sY) * sWidth + sX * 2] = nPal[sY * 127 / sHeight + 1]
                                    }
                                }
                            }
                            // logarithmic, combine bins, fast
                            1 -> {
                                Arrays.fill(specBuf, 0)
                                sZ = 0
                                for (sX in 0 until nBands) {
                                    sP = 0f
                                    sB = 2.0.pow(sX * 9.0 / (nBands - 1)).toInt()
                                    if (sB <= sZ) sB = sZ + 1  // make sure it uses at least 1 FFT bin
                                    if (sB > 511) sB = 511
                                    while (sZ < sB) {
                                        if (sP < fft[1 + sZ]) sP = fft[1 + sZ]
                                        sZ++
                                    }
                                    // scale it (sqrt to make low values more visible)
                                    sY = (sqrt(sP.toDouble()) * 3 * sHeight - 4).toInt()
                                    if (sY <= 0) sY = 0 else if (sY >= sHeight) sY = sHeight - 1  // cap it
                                    if (sY > sPk[sX]) sPk[sX] = sY else sPk[sX] = sPk[sX] - 3
                                    if (sPk[sX] <= 0) sPk[sX] = 0
                                    sS = (sHeight - 1 - sPk[sX]) * sWidth + sX * (sWidth / nBands)
                                    Arrays.fill(specBuf, sS, sS + sWidth / nBands * 9 / 10, dotColor)
                                    // draw bar
                                    while (--sY >= 0) {
                                        sS = (sHeight - 1 - sY) * sWidth + sX * (sWidth / nBands)
                                        Arrays.fill(specBuf, sS, sS + sWidth / nBands * 9 / 10, nPal[sY * 127 / sHeight + 1])
                                    }
                                }
                            }
                            // logarithmic, combine bins, slowly falling
                            2 -> {
                                Arrays.fill(specBuf, 0)
                                for (sX in 0 until nBands) {
                                    sY = (sqrt(fft[sX]) * 3.3 * sHeight - 3).toInt()
                                    sY = if (sY >= sHeight) sHeight - 1 else if (sY <= 0) 0 else sY
                                    sPk[sX] = if (sY >= sPk[sX]) sY else sPk[sX] - 5
                                    if (sPk[sX] <= 0) sPk[sX] = 0
                                    // draw bar
                                    sY = sPk[sX]
                                    while (--sY >= 0) {
                                        sS = (sHeight - 1 - sY) * sWidth + sX * (sWidth / nBands)
                                        Arrays.fill(specBuf, sS, sS + sWidth / nBands * 9 / 10, nPal[sY * 127 / sHeight + 1])
                                    }
                                }
                            }
                            // 3d
                            else -> {
                                for (sX in 0 until sHeight) {
                                    // scale it (sqrt to make low values more visible)
                                    sY = (sqrt(fft[sX + 1].toDouble()) * 3 * 127).toInt()
                                    if (sY > 127) sY = 127  // cap it
                                    specBuf[(sHeight - 1 - sX) * sWidth + specPos] = nPal[128 + sY]  // plot it
                                }
                                // move marker onto next position
                                specPos = (specPos + 1) % sWidth
                                for (sX in 0 until sHeight) specBuf[sX * sWidth + specPos] = Color.WHITE
                            }
                        }
                    }
                }
                // update the display
                bitmap = Bitmap.createBitmap(specBuf, sWidth, sHeight, Bitmap.Config.ARGB_8888)
                bitmap = Bitmap.createScaledBitmap(bitmap, nImgWidth.toInt(), nImgHeight.toInt(), true)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bindMain.imgSpec.setImageBitmap(bitmap)

                handler.postDelayed(this, refreshRate)

            }
            // stop playing
            else {
                clickStop()
                resetPos()
                bindMain.txtTime.text = time
            }
        }
    }

    // sync callback for audio only
    private val syncProc = BASS.SYNCPROC { handle: Int, channel: Int, data: Int, user: Any? ->
        if (bindMain.posStart.visibility == View.INVISIBLE) {
            posStart = 0L
        }
        BASS.BASS_ChannelSetPosition(hdlStream, posStart, BASS.BASS_POS_BYTE)
        BASS.BASS_ChannelPlay(hdlStream, false)
    }

    // setup palette
    init {
        for (i in 1..127) {  // for waveform
            nPal[i] = Color.rgb((1.7 * i).toInt(), 154, 255)
        }
        for (i in 0..31) {  // for 3d
            nPal[128 + 0 + i] = Color.rgb(0, 0, 8 * i)
            nPal[128 + 32 + i] = Color.rgb(8 * i, 0, 255)
            nPal[128 + 64 + i] = Color.rgb(255, 8 * i, 8 * (31 - i))
            nPal[128 + 96 + i] = Color.rgb(255, 255, 8 * i)
        }
    }

    private fun showToast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        toast?.show()
    }

    // convert seconds -- > hh:mm:ss.ms or mm:ss.ms for displaying only
    private fun secToMark(nSec: Double): String {
        var hh = 0.0
        val ss: Double = nSec.rem(60)
        var mm: Double = floor(nSec / 60)
        if (mm >= 60) {
            hh = floor(mm / 60)
            mm = floor(mm.rem(60))
        }
        var cTime = String.format("%s:%s", String.format(null, "%02d", mm.toInt()), String.format(null, "%05.2f", ss))
        if (hh > 0) cTime = String.format("%s:%s", String.format(null, "%02d", hh.toInt()), cTime)

        return cTime
    }

    private fun keepScreenOn(bKeep: Boolean = false) {
        if (bKeep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun drawPeak() {
        arrPeak.clear()
        // len * 1000 / 20 + 1 is the total peak data that can be obtained
        // and is also the required size of the array
        val nLen = (len * 50 + 1).toInt()
        // Re-establish the file stream wavStream
        // the last parameter is: BASS_STREAM_DECODE
        // so that the waveform data can be read in advance
        val wavStream = BASS.BASS_StreamCreateFile(fileName, 0, 0, BASS.BASS_STREAM_DECODE)
        // Loop through the peak data to fill the array
        repeat(nLen) { arrPeak.add(BASS.BASS_ChannelGetLevel(wavStream)) }
        BASS.BASS_StreamFree(wavStream)

        bitmap = Bitmap.createBitmap(nLen, nImgHeight.toInt(), Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
        // draw audio spectrum (peak level)
        paint.strokeWidth = 2f
        paint.color = Color.rgb(40, 154, 255)
        val nCh = nImgHeight / 2f
        for (i in 0 until nLen) {
            val k = i.toFloat()
            val nL = arrPeak[i] shr 16
            val nR = arrPeak[i] and 0xFFFF

            if (flgStereo) {
                val nUp = nCh - nL.toFloat() / 32768 * nCh
                val nDw = nCh + nR.toFloat() / 32768 * nCh
                canvas.drawLine(k, nUp, k, nDw, paint)
            }
            else {
                val nPk = nImgHeight - (nL + nR).toFloat() / 2 / 32767 * nImgHeight
                canvas.drawLine(k, nPk, k, nImgHeight, paint)
            }
        }

        bitmap = Bitmap.createScaledBitmap(bitmap, nImgWidth.toInt(), nImgHeight.toInt(), true)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        bindMain.imgWave.setImageBitmap(bitmap)
    }

    private fun freeStream() {
        BASS.BASS_ChannelFree(hdlStream)
        BASS.BASS_StreamFree(hdlStream)
        BASS.BASS_ChannelRemoveSync(hdlStream, hdlSync)
    }

    private fun initAudio() {
        BASS.BASS_ChannelGetInfo(hdlStream, iCh)
        mp3Len = BASS.BASS_ChannelGetLength(hdlStream, BASS.BASS_POS_BYTE)
        bBuf = ByteBuffer.allocateDirect(iCh.chans * sWidth * 4)  // allocate buffer for data
        bBuf.order(ByteOrder.LITTLE_ENDIAN)  // little-endian byte order
    }

    private fun chkImgSize() {
        nImgWidth = bindMain.imgSpec.width.toFloat()
        nImgHeight = bindMain.imgSpec.height.toFloat()
    }

    private fun chkLoop() {
        if (bindMain.swtLoop.isChecked) {
            BASS.BASS_ChannelFlags(hdlStream, BASS.BASS_SAMPLE_LOOP, BASS.BASS_SAMPLE_LOOP)
        }
        else {
            BASS.BASS_ChannelFlags(hdlStream, 0, BASS.BASS_SAMPLE_LOOP)
        }
    }

    private fun resetPos() {
        specPos = 0
        Arrays.fill(sPk, 0)
        Arrays.fill(specBuf, 0)
        bindMain.barLeft.progress = 0
        bindMain.barRight.progress = 0
        bindMain.posLine.translationX = 0f
        bindMain.posLine.visibility = View.INVISIBLE
        bindMain.posStart.visibility = View.INVISIBLE
        bindMain.posEnd.visibility = View.INVISIBLE
        bindMain.imgSpec.setImageDrawable(null)
    }

    private fun onSingleClick(x: Float) {
        bindMain.posLine.translationX = x
        BASS.BASS_ChannelSetPosition(hdlStream, (x / nImgWidth * mp3Len).toLong(), BASS.BASS_POS_BYTE)
        clickPlay()
    }

    private fun onDoubleClick(x: Float) {
        bindMain.posStart.translationX = x
        bindMain.posStart.visibility = View.VISIBLE
        posStart = (x / nImgWidth * mp3Len).toLong()
    }

    private fun onLongPress(x: Float) {
        bindMain.posEnd.translationX = x
        bindMain.posEnd.visibility = View.VISIBLE
        posEnd = (x / nImgWidth * mp3Len).toLong()
        BASS.BASS_ChannelRemoveSync(hdlStream, hdlSync)
        hdlSync = BASS.BASS_ChannelSetSync(hdlStream, BASS.BASS_SYNC_POS, posEnd, syncProc, 0)
    }

    private fun clickPlay() {
        flgPlay = true
        keepScreenOn(true)
        bindMain.posLine.visibility = View.VISIBLE
        BASS.BASS_ChannelPlay(hdlStream, false)
        handler.postDelayed(timerSpectrum, refreshRate)
        bindMain.btnPlay.setImageResource(R.drawable.ic_pause)
    }

    private fun clickStop() {
        flgPlay = false
        keepScreenOn(false)
        BASS.BASS_ChannelStop(hdlStream)
        handler.removeCallbacks(timerSpectrum)
        bindMain.btnPlay.setImageResource(R.drawable.ic_play)
    }

    private fun openFile() {
        val list = filePath.list() ?: emptyArray()
        fileList = if (filePath.path != "/" && filePath != dirRoot) {
            val nList = arrayOfNulls<String>(list.size + 1)
            nList[0] = ".."
            arraycopy(list, 0, nList, 1, list.size)
            nList.requireNoNulls()
        }
        else list
        fileList.sortWith(String.CASE_INSENSITIVE_ORDER)
        MaterialAlertDialogBuilder(this)
                .setIcon(R.drawable.ic_audio)
                .setTitle("Choose an audio file")
                .setItems(fileList) { dialog, which ->
                    val sel: File = if (fileList[which] == "..") filePath.parentFile as File else File(filePath, fileList[which])
                    if (sel.isDirectory && sel != filePath) {
                        filePath = sel
                        openFile()
                    }
                    else {
                        clickStop()
                        freeStream()
                        fileName = sel.path
                        hdlStream = BASS.BASS_StreamCreateFile(fileName, 0, 0, 0)
                        if (hdlStream == 0) {
                            errorMsg("Can't play the file")
                            bindMain.imgWave.setImageDrawable(null)
                            bindMain.txtInfo.text = String.format("")
                            return@setItems
                        }
                        len = BASS.BASS_ChannelBytes2Seconds(hdlStream, BASS.BASS_ChannelGetLength(hdlStream, BASS.BASS_POS_BYTE))
                        time = secToMark(len)
                        resetPos(); showFileName()
                        chkImgSize(); initAudio(); drawPeak()
                    }
                }.show()
    }

    private fun errorMsg(str: String) {
        showToast(String.format(null, "%s\n(error code: %d)", str, BASS.BASS_ErrorGetCode()))
    }

    private fun showFileName() {
        bindMain.txtInfo.text = String.format("Audio: %s", File(fileName).name)
        bindMain.txtTime.text = time
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateTheMainView() {
        bindMain = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bindMain.root)

        // free before initial device
        BASS.BASS_Free()

        // initialize default output device
        if (!BASS.BASS_Init(-1, 44100, 0)) {
            val snackBar = Snackbar.make(
                findViewById(android.R.id.content),
                "WARNING\nCannot initialize this phone\'s sound device.\n(Error code: ${BASS.BASS_ErrorGetCode()})",
                Snackbar.LENGTH_LONG
            )
            val sbText: TextView = snackBar.view.findViewById(com.google.android.material.R.id.snackbar_text)
            sbText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0)
            sbText.compoundDrawablePadding = 48
            snackBar.duration = 5000
            snackBar.setTextMaxLines(3)
            snackBar.show()
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 6000)
            return
        }

        var plug: Int
        for (lib in libs) {
            plug = BASS.BASS_PluginLoad(lib, 0)  // loading all plugins
            Log.d("tag", "$lib, $plug")
            if (plug != 0) {
                val info = BASS.BASS_PluginGetInfo(plug)  // get plugin info
                for (a in 0 until info.formatc) {
                    Log.d("tag", "${info.formats[a].ctype}, ${info.formats[a].name}, ${info.formats[a].exts}")
                }
            }
        }

        chkLoop()
        resetPos()
        if (!filePath.exists()) filePath = dirRoot

        val file = File(filePath, "start.mp3")
        if (file.exists()) {
            fileName = file.toString().trim()
            hdlStream = BASS.BASS_StreamCreateFile(fileName, 0, 0, 0)
            if (hdlStream == 0) {
                errorMsg("Can't play the audio")
                openFile()
                return
            }
            // When using BASS_ChannelGetLevel to obtain the peak value
            // it is in units of 20ms; first obtain the total time
            len = BASS.BASS_ChannelBytes2Seconds(hdlStream, BASS.BASS_ChannelGetLength(hdlStream, BASS.BASS_POS_BYTE))
            time = secToMark(len)
            showFileName()
            Handler(Looper.getMainLooper()).postDelayed({
                chkImgSize(); initAudio(); drawPeak()
            }, 10)  // must delay a little, waiting for image view width and height
        }
        else openFile()

        bindMain.imgWave.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x  // for long press position
                    nTapDown = System.currentTimeMillis()
                    handler.removeCallbacks(timerOneTap)
                    handler.postDelayed(timerLPress, LONG_PRESS_TIME_DELTA)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    upX = event.x  // for single or double click position
                    if ((System.currentTimeMillis() - nTapDown) <= LONG_PRESS_TIME_DELTA) {
                        handler.removeCallbacks(timerLPress)
                        if ((nTapDown - nLastTap) < DOUBLE_CLICK_TIME_DELTA) {
                            onDoubleClick(upX)
                        }
                        else {
                            handler.postDelayed(timerOneTap, DOUBLE_CLICK_TIME_DELTA)
                        }
                        nLastTap = nTapDown
                    }
                    true
                }

                else -> super.onTouchEvent(event)
            }
        }

        bindMain.imgSpec.setOnClickListener {
            specMode = (specMode + 1) % 6  // change the spectrum mode
            Arrays.fill(specBuf, 0)  // clear display
            Arrays.fill(sPk, 0)  // clear peak level
            specPos = 0
        }

        bindMain.btnOpen.setOnClickListener {
            openFile()
        }

        bindMain.btnPlay.setOnClickListener {
            if (flgPlay) clickStop() else clickPlay()
        }

        bindMain.btnRemove.setOnClickListener {
            bindMain.posStart.visibility = View.INVISIBLE
            bindMain.posEnd.visibility = View.INVISIBLE
            BASS.BASS_ChannelRemoveSync(hdlStream, hdlSync)
        }

        bindMain.swtLoop.setOnClickListener {
            chkLoop()
        }
    }

    private fun reqStoragePermissionsR() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${packageName}")
            openSettingsLauncher.launch(intent)
        }
    }

    private fun reqStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.setData(Uri.parse("package:${packageName}"))
            startActivity(intent)
        }
        else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), FILE_PERMISSION_REQUEST_CODE)
        }
    }

    private fun chkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun detailPermission(mod: String = "file") {
        val intent = when (mod) {
            "file" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }

            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
        intent.setData(Uri.parse("package:${packageName}"))
        startActivity(intent)
    }

    private fun showDate(date: Calendar) {
        val tms = date.timeInMillis
        bindHead.clDate.text = fDate.format(tms)
        bindHead.clDay.text = fDay.format(tms)
    }

    private fun inflateTheHeadView() {
        bindHead = ActivityHeadBinding.inflate(layoutInflater)
        setContentView(bindHead.root)

        showDate(Calendar.getInstance())

        bindHead.clCalendar.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val today = Calendar.getInstance()
            today.set(year, month, dayOfMonth)
            showDate(today)
        }

        bindHead.clLinear.setOnLongClickListener {
            detailPermission()
            true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (chkStoragePermissions()) {
            inflateTheMainView()
        }
        else {
            // get the permission of read internal storage (not external storage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // wait for permission result, only for android 11 and above
                openSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (chkStoragePermissions()) inflateTheMainView() else inflateTheHeadView()
                }
                reqStoragePermissionsR()
            }
            else reqStoragePermissions()
        }

//        Handler(Looper.getMainLooper()).postDelayed({
//        }, 300)

    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).postDelayed({
            chkImgSize()
            drawPeak()
            if (!flgPlay) {
                bindMain.posLine.translationX = BASS.BASS_ChannelGetPosition(hdlStream, BASS.BASS_POS_BYTE).toFloat() / mp3Len * nImgWidth
                bitmap = Bitmap.createBitmap(specBuf, sWidth, sHeight, Bitmap.Config.ARGB_8888)
                bitmap = Bitmap.createScaledBitmap(bitmap, nImgWidth.toInt(), nImgHeight.toInt(), true)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bindMain.imgSpec.setImageBitmap(bitmap)
            }
        }, 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // wait for permission result, only for android 10 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && requestCode == FILE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.first() == PackageManager.PERMISSION_GRANTED) inflateTheMainView()
            else inflateTheHeadView()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toast?.cancel()
        BASS.BASS_ChannelRemoveSync(hdlStream, hdlSync)
        BASS.BASS_ChannelStop(hdlStream)
        BASS.BASS_ChannelFree(hdlStream)
        BASS.BASS_StreamFree(hdlStream)
        BASS.BASS_PluginFree(0)  // unload all plugins
        BASS.BASS_Stop()
        BASS.BASS_Free()
    }

}