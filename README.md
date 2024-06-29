## Spectrum (Audio Spectrum)
这是关于libbass.so音频库频谱的简单示例.<br>
主要是将其Android库(bass24-android.zip)中的Java示范代码spectrum部分转换为Kotlin版本.<br>

在Android设备显示音频频谱的实用意义不是很大, 主要是保持亮屏比较耗电, 另外CPU占用比也相对高些.<br>
音频插件只得手动加载, 不知为什么applicationInfo.nativeLibraryDir返回是Null.<br>
即使路径对了, 目录下没有任何so文件, 但apk里是打包了插件so的.<br>
使用了默认的jniLibs目录也不起作用, 不了解Android把so文件安装到什么地方去了.<br>

以上简单示例的代码, 在Android Studio使用Kotlin 1.9.23编译通过.<br>
<img src="https://github.com/cygstar/Spectrum/blob/main/assets/Spectrum_01.png" width="160">
<img src="https://github.com/cygstar/Spectrum/blob/main/assets/Spectrum_02.png" width="160">
<img src="https://github.com/cygstar/Spectrum/blob/main/assets/Spectrum_03.png" width="160">
<img src="https://github.com/cygstar/Spectrum/blob/main/assets/Spectrum_04.png" width="160">
<img src="https://github.com/cygstar/Spectrum/blob/main/assets/Spectrum_05.png" width="160"><br>

其中,<br>
在声频频谱(上部): 单击 -> 跳到点击位置并播放; 双击 -> 设置循环播放起点; 长按 -> 设置循环播放终点; 按钮- -> 清除循环播放.<br>
在动态频谱(下部): 单击 -> 切换频谱模式<br>

以上简单示例的代码甚为丑陋且不健壮, 不足以作为参考, 仅仅提供一点思路的提示吧.<br>
(比如播放前并没有检查是否正确打开文件, 设定/清除循环起终点时没有判断两点的前后位置等等等, 但程序还能运行...)<br>

This is a basic example illustrating the spectrum functionality of the libbass.so audio library. The main objective is to adapt the spectrum section of its Java demo code from the Android library (bass24-android.zip) into a Kotlin version.<br>
Happy coding.<br>

关于bass.dll的详细, 请访问以下网站.<br>
For any detailed information, please visit:<br>
https://www.un4seen.com/<br>
