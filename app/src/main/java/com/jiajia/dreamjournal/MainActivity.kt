package com.jiajia.dreamjournal

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        // 静态变量，生命周期伴随 App 进程。
        // true 表示这是 App 进程启动后的第一次加载。
        private var isAppProcessFirstLoad = true
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastSafeAreaTopDp: Float = 0f
    private var isPageLoaded = false
    
    // 用于保存导出的文件内容
    private var pendingExportContent: String? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            filePathCallback?.onReceiveValue(result.data?.data?.let { arrayOf(it) })
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "存储权限被拒绝，文件无法保存。", Toast.LENGTH_LONG).show()
        }
    }

    // 创建文件导出启动器 (SAF)
    private val createFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingExportContent?.let { content ->
                    writeContentToUri(uri, content)
                }
            }
        } else {
             Toast.makeText(this, "导出已取消", Toast.LENGTH_SHORT).show()
        }
        // 清理暂存内容
        pendingExportContent = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 0. 设置刘海屏适配模式，允许内容延伸到刘海区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        // 1. 设置沉浸式，让布局延伸到状态栏和导航栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val splashScreen = installSplashScreen()
        // 保持启动画面直到网页加载完成
        splashScreen.setKeepOnScreenCondition {
            !isPageLoaded
        }
        // 自定义 Splash 退出动画
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            val splashScreenView = splashScreenViewProvider.view

            // 配合淡出效果
            val alpha = ObjectAnimator.ofFloat(
                splashScreenView,
                android.view.View.ALPHA,
                1f,
                0f
            )
            alpha.interpolator = FastOutSlowInInterpolator()
            alpha.duration = 400L

            // 动画结束后移除 SplashView
            val animatorSet = android.animation.AnimatorSet()
            animatorSet.playTogether(alpha)
            animatorSet.doOnEnd {
                splashScreenViewProvider.remove()
            }
            animatorSet.start()
        }
        
        setContentView(R.layout.activity_main)

        // 2. 隐藏状态栏和导航栏
        hideSystemBars()

        setupBackPressHandler()
        requestStoragePermissionsIfNeeded()

        webView = findViewById(R.id.webview)
        setupWebView()
        webView.loadUrl("https://xiaojia736.github.io/dream-journal/")
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // 隐藏状态栏和导航栏
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        // 设置行为：当用户从屏幕边缘滑动时，系统栏会短暂地出现
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        // 注册 JS 接口
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidExport")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectSafeArea(lastSafeAreaTopDp)
                
                // 使用 postVisualStateCallback 确保 WebView 已经完成了当前的绘制
                view?.postVisualStateCallback(123, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        // 这样可以最大程度避免 SPA (单页应用) 刚加载完 HTML 骨架时的白屏
                        view.postDelayed({
                            isPageLoaded = true

                        }, 0)
                    }
                })
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            
            if (isAppProcessFirstLoad) {
                // App 进程首次启动：强制不使用缓存，从网络拉取最新
                cacheMode = WebSettings.LOAD_NO_CACHE
                // 标记已处理，后续 Activity 重建（如旋转屏幕、深色模式切换）将使用缓存
                isAppProcessFirstLoad = false
            } else {
                // 进程生命周期内的后续加载：使用默认缓存策略（遵循 HTTP 头或使用已缓存资源）
                cacheMode = WebSettings.LOAD_DEFAULT
            }
        }

        // 监听 WindowInsets 变化，获取刘海屏高度
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, windowInsets ->
            val displayCutout = windowInsets.displayCutout
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 优先使用 displayCutout 的 safeInsetTop，如果没有则使用 systemBars 的 top
            // 注意：当隐藏状态栏时，systemBars().top 可能还是会有值（表示状态栏原本的高度），
            // 但我们的目的是避开物理遮挡（刘海），所以主要关注 displayCutout。
            // 如果没有刘海，且隐藏了状态栏，我们可能希望 top 为 0。
            // 但如果用户希望即使无刘海也保留状态栏高度作为 padding（虽然状态栏隐藏了），可以使用 insets.top。
            // 这里假设主要为了避让刘海。
            val topPx = displayCutout?.safeInsetTop ?: 0

            val density = resources.displayMetrics.density
            val topDp = topPx / density

            if (topDp != lastSafeAreaTopDp) {
                lastSafeAreaTopDp = topDp
                injectSafeArea(topDp)
            }

            // 返回 windowInsets 以允许 WebView 内部继续处理（例如软键盘适配）
            windowInsets
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            Log.d("ExportDebug", "Download requested for URL: $url")
            when {
                url.startsWith("data:") -> {
                    saveDataUrlToDownloads(this, url, contentDisposition, mimetype)
                }
                url.startsWith("blob:") -> {
                    val script = """
                        (async function() {
                            try {
                                const response = await fetch('$url');
                                if (!response.ok) throw new Error('Fetch failed: ' + response.status);
                                const blob = await response.blob();
                                const reader = new FileReader();
                                return await new Promise((resolve, reject) => {
                                    reader.onload = () => resolve(reader.result);
                                    reader.onerror = () => reject('Reader error');
                                    reader.readAsDataURL(blob);
                                });
                            } catch (e) {
                                console.error('Blob export error:', e);
                                return 'error: ' + e.message;
                            }
                        })();
                    """
                    webView.evaluateJavascript(script) { result ->
                        Log.d("ExportDebug", "Blob converted result: $result")
                        
                        if (result != null && result != "null" && !result.contains("\"error:")) {
                            // 去掉首尾的双引号
                            val cleanResult = result.removeSurrounding("\"")
                            saveDataUrlToDownloads(this, cleanResult, contentDisposition, mimetype)
                            
                            // 关键：通知 Web 端释放资源
                            webView.post {
                                webView.evaluateJavascript("if(window.onExportComplete) window.onExportComplete();", null)
                            }
                        } else {
                            val errorMsg = if (result != null) result else "Unknown error"
                            Toast.makeText(this, "导出失败: $errorMsg", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    val request = DownloadManager.Request(url.toUri()).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        setDescription("正在下载文件...")
                        setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                    }
                    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(applicationContext, "下载已开始...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                fileChooserLauncher.launch(intent)
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d("WebViewConsole", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                }
                return true
            }
        }
    }

    private fun injectSafeArea(topDp: Float) {
        // 将安全区域高度注入到 CSS 变量 --safe-area-top 中
        // 网页端可以使用 var(--safe-area-top) 来设置 padding-top
        val js = "document.documentElement.style.setProperty('--safe-area-top', '${topDp}px');"
        webView.evaluateJavascript(js, null)
    }

    private fun saveDataUrlToDownloads(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
        // ... (保持不变，或根据需要重构供 Interface 调用)
        // 为了复用代码，我们可以把保存逻辑提取出来
        // 这里暂时不改动它，而是新增一个专门处理 JSON 字符串的方法
    }

    /**
     * 处理来自 JS Interface 的纯 JSON 字符串导出
     * 修改为：弹出系统文件选择器让用户选择保存位置
     */
    fun saveJsonContentToDownloads(jsonContent: String) {
        runOnUiThread {
            pendingExportContent = jsonContent
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "dream_journal_backup_${sdf.format(Date())}.json"
            
            // 使用 Storage Access Framework (SAF) 启动文件创建意图
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            createFileLauncher.launch(intent)
        }
    }

    private fun writeContentToUri(uri: Uri, content: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "导出成功", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // 内部类：JS 接口
    private inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun exportData(jsonContent: String) {
            Log.d("AndroidExport", "Received data from JS: length=${jsonContent.length}")
            saveJsonContentToDownloads(jsonContent)
        }
    }
}
