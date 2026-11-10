package com.moye.pdfink

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), InkView.Host {

    private val vm: MainViewModel by viewModels()

    private data class MenuAction(
        val icon: Int, val label: String,
        val active: () -> Boolean, val run: () -> Unit
    )

    private val colors = intArrayOf(
        0xFF222226.toInt(), 0xFFE5484D.toInt(), 0xFF2F6FED.toInt(),
        0xFF1E9E62.toInt(), 0xFFF2A03D.toInt()
    )
    // 5 档数值与 UI 预览都由 BrushSizes 一处定义
    private val widths = BrushSizes.PEN
    private val eraserWidths = BrushSizes.ERASER
    private val highlighterAlpha = 92

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var inkStore: InkStore
    private lateinit var recents: Recents
    private lateinit var sessionRepo: SessionRepository
    private lateinit var snapshots: SnapshotKeeper
    private var repo: PdfRepo? = null
    private var adapter: PagesAdapter? = null

    private val history: InkHistory get() = vm.history
    private val state: UiState get() = vm.state
    private var colorIndex: Int
        get() = vm.colorIndex
        set(v) { vm.colorIndex = v }
    private var widthIndex: Int
        get() = vm.widthIndex
        set(v) { vm.widthIndex = v }
    private var eraserIndex: Int
        get() = vm.eraserIndex
        set(v) { vm.eraserIndex = v }
    private var currentTool: Int
        get() = vm.brushTool
        set(v) { vm.brushTool = v }
    private var lastDrawTool: Int
        get() = vm.lastDrawTool
        set(v) { vm.lastDrawTool = v }
    private var currentShape: Int
        get() = vm.brushShape
        set(v) { vm.brushShape = v }
    private var barsHidden: Boolean
        get() = vm.barsHidden
        set(v) { vm.barsHidden = v }
    private var rightHanded: Boolean
        get() = vm.rightHanded
        set(v) { vm.rightHanded = v }
    private var currentZoom: Float
        get() = vm.currentZoom
        set(v) { vm.currentZoom = v }
    private var currentPanX = 0f
    private var currentPanY = 0f
    private var focusKey: String
        get() = vm.focusKey
        set(v) { vm.focusKey = v }
    private var lastTriTap: Long
        get() = vm.lastTriTap
        set(v) { vm.lastTriTap = v }
    private var tempEraserActive: Boolean
        get() = vm.tempEraserActive
        set(v) { vm.tempEraserActive = v }
    private var toolBeforeEraser: Int
        get() = vm.toolBeforeEraser
        set(v) { vm.toolBeforeEraser = v }
    private var pendingPdfPages: List<Int> = emptyList()
    private var pendingPdfWidth: Int = 1240
    /** 用户是否手动切过横屏显示模式；没切过就始终给 WIDTH 铺满 */
    private var modeChosen = false
    private var pendingExport: (() -> Unit)? = null
    private var sysTop = 0
    private var sysBottom = 0
    private var safeL = 0
    private var safeT = 0
    private var safeR = 0
    private var safeB = 0
    private val brushSlots = ArrayList<View>()
    private lateinit var btnTempEraser: ImageButton

    private val activeViews = HashMap<String, InkView>()
    private val colorDots = ArrayList<View>()
    private val widthDots = ArrayList<View>()

    private lateinit var root: View
    private lateinit var pager: ViewPager2
    private lateinit var edgeLeft: View
    private lateinit var edgeRight: View
    private lateinit var topBar: FrameLayout
    private lateinit var toolsScroll: HorizontalScrollView
    private lateinit var toolsStrip: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var emptyState: View
    private lateinit var homeStage: FrameLayout
    private lateinit var homeHero: View
    private lateinit var homeScroll: View
    private lateinit var btnHomeMore: ImageButton
    private lateinit var homeShelf: LinearLayout
    private lateinit var colorRow: LinearLayout
    private lateinit var widthRow: LinearLayout
    private lateinit var brushRow: LinearLayout
    private lateinit var shapeBar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var tvPage: TextView
    private lateinit var btnPen: ImageButton
    private lateinit var btnHighlight: ImageButton
    private lateinit var btnShape: ImageButton
    private lateinit var btnLasso: ImageButton
    private lateinit var btnNote: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnHide: ImageButton
    private lateinit var btnShow: ImageButton
    private lateinit var btnBrowse: ImageButton
    private lateinit var btnBookmark: ImageButton

    // ----- InkView.Host -----
    override val tool: Int get() = currentTool
    override val inkColor: Int get() = colors[colorIndex]
    override val inkWidth: Float
        get() = widths[widthIndex] * if (currentTool == Tools.HIGHLIGHT) 2.4f else 1f
    override val inkAlpha: Int get() = if (currentTool == Tools.HIGHLIGHT) highlighterAlpha else 255
    override val inkShape: Int get() = currentShape
    override val eraserWidth: Float get() = eraserWidths[eraserIndex]
    override val browseMode: Boolean get() = state.browse
    override val stylusOnly: Boolean get() = state.stylusOnly
    override val palmGuard: Boolean get() = state.palmGuard
    override val activeLayer: Int get() = state.activeLayer
    override val visibleLayers: Set<Int> get() = state.visibleLayers
    override val store: InkStore get() = inkStore
    override val ioScope: CoroutineScope get() = workScope
    override fun onFocus(key: String) { focusKey = key }
    override fun onCommit(key: String, before: List<Mark>, after: List<Mark>) {
        history.push(listOf(InkHistory.Change(key, before, after)))
        refreshUndoState()
    }
    override fun onSelectionChanged(active: Boolean) {
        selectionBar.visibility = if (active && !barsHidden && !state.browse) View.VISIBLE
        else View.GONE
    }
    override fun onRequestNote(x: Float, y: Float, existing: String?, cb: (String?) -> Unit) {
        val et = EditText(this).apply {
            setText(existing ?: "")
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icon))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 6
            requestFocus()
        }
        val d = AlertDialog.Builder(this).setView(et)
            .setPositiveButton("✓") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("×", null)
            .create()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        d.show()
    }

    // ----- SAF -----
    private val openDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            hideSystemBars()
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }
            if (openUri(uri)) {
                prefs().edit().putString("uri", uri.toString()).apply()
                recents.add(uri, recents.displayName(uri), vm.currentFp,
                    repo?.count ?: 0)
            }
        }

    private val createBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) return@registerForActivityResult
            workScope.launch {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { inkStore.exportTo(it) }
                    true
                } catch (e: Exception) { false }
                withContext(Dispatchers.Main) { flash(if (ok) "✓" else "!") }
            }
        }

    private val openBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            showImportMode(uri)
        }

    /** Android 9 及以下 insertImage 需要运行时权限；授权后自动续跑原动作。 */
    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingExport
            pendingExport = null
            if (granted) action?.invoke() else flash("!")
        }

    private val createPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) return@registerForActivityResult
            val r = repo ?: return@registerForActivityResult
            val pages = ArrayList(pendingPdfPages)
            workScope.launch {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { o ->
                        PdfExporter.export(
                            r, inkStore, pages, o, state.tint, state.brightness,
                            state.visibleLayers, state.effectiveSpread, pendingPdfWidth,
                            state.pageRotation
                        ) { _, _ -> }
                    } ?: false
                } catch (e: Exception) { false }
                withContext(Dispatchers.Main) { flash(if (ok) "✓" else "!") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        hideSystemBars()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { v ->
                // 系统误把状态栏露出来时立刻重新隐藏，禁止边缘上滑滑出
                if (v and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) hideSystemBars()
            }
        }
        inkStore = InkStore(this)
        recents = Recents(this)
        sessionRepo = SessionRepository(this)
        snapshots = SnapshotKeeper(java.io.File(filesDir, "snapshots"))
        vm.attach(sessionRepo)
        vm.createdPage = savedInstanceState?.getInt("moye_page", -1) ?: -1
        loadPrefs()
        applyAwake()
        applySystemDark(null)

        root = findViewById(R.id.root)
        pager = findViewById(R.id.pager)
        edgeLeft = findViewById(R.id.edgeLeft)
        edgeRight = findViewById(R.id.edgeRight)
        topBar = findViewById(R.id.topBar)
        toolsScroll = findViewById(R.id.toolsScroll)
        toolsStrip = findViewById(R.id.toolsStrip)
        bottomBar = findViewById(R.id.bottomBar)
        emptyState = findViewById(R.id.emptyState)
        homeStage = findViewById(R.id.homeStage)
        homeHero = findViewById(R.id.homeHero)
        homeScroll = findViewById(R.id.homeScroll)
        btnHomeMore = findViewById(R.id.btnHomeMore)
        homeShelf = findViewById(R.id.homeShelf)
        colorRow = findViewById(R.id.colorRow)
        widthRow = findViewById(R.id.widthRow)
        brushRow = findViewById(R.id.brushRow)
        shapeBar = findViewById(R.id.shapeBar)
        selectionBar = findViewById(R.id.selectionBar)
        seek = findViewById(R.id.seek)
        tvPage = findViewById(R.id.tvPage)
        btnPen = findViewById(R.id.btnPen)
        btnHighlight = findViewById(R.id.btnHighlight)
        btnShape = findViewById(R.id.btnShape)
        btnLasso = findViewById(R.id.btnLasso)
        btnNote = findViewById(R.id.btnNote)
        btnEraser = findViewById(R.id.btnEraser)
        btnHide = findViewById(R.id.btnHide)
        btnShow = findViewById(R.id.btnShow)
        btnBrowse = findViewById(R.id.btnBrowse)
        btnBookmark = findViewById(R.id.btnBookmark)

        buildDots()
        buildBrushSlots()
        setupTempEraser()
        setupClicks()
        setupInsets()
        applyHandedness()
        applyOrientationLayout()
        updatePagerInput()

        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                focusKey = "p${DisplayMode.logical(
                    state.displayMode, state.landscape, position)}"
                updatePage(position)
                updatePagerInput(position)
                refreshBookmark()
                saveSessionNow()
            }
        })

        val saved = prefs().getString("uri", null)
        // 冷启动且已有历史卷子 → 先落在书架首页；点卡片才回卷子，会话照旧恢复。
        // 系统重建 Activity（savedInstanceState 非空）或还没有历史时，直接回上一份卷子。
        val direct = savedInstanceState != null || recents.all().isEmpty()
        if (saved != null && direct) {
            try { openUri(Uri.parse(saved)) } catch (e: Exception) { showHome() }
        } else showHome()
    }

    // ----- prefs -----
    private fun prefs() = getSharedPreferences("moye", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        colorIndex = p.getInt("color", 0).coerceIn(0, colors.lastIndex)
        widthIndex = p.getInt("width", BrushSizes.DEFAULT)
            .coerceIn(0, widths.lastIndex)
        eraserIndex = p.getInt("eraser", BrushSizes.DEFAULT)
            .coerceIn(0, eraserWidths.lastIndex)
        currentShape = p.getInt("shape", Shapes.LINE)
        state.tint = p.getInt("tint", PageFilter.WHITE).coerceIn(0, 2)
        state.brightness = p.getFloat("brightness", 0f)
        // v16：默认浏览。老版本存过 false 的也算升级首次，强制回浏览；
        // 之后用户切到做题会写进偏好，重开保持上次模式。
        val browseMigrated = p.getBoolean("browseMigrated", false)
        state.browse = if (browseMigrated) p.getBoolean("browse", true) else true
        if (!browseMigrated) {
            prefs().edit().putBoolean("browseMigrated", true).apply()
        }
        state.spread = p.getBoolean("spread", false)
        state.displayMode = p.getInt("displayMode", DisplayMode.WIDTH)
            .coerceIn(DisplayMode.FIT, DisplayMode.DOUBLE)
        modeChosen = p.getBoolean("modeChosen", false)
        state.stylusOnly = p.getBoolean("stylus", false)
        state.palmGuard = p.getBoolean("palm", true)
        state.edgeFlip = p.getBoolean("edge", true)
        state.activeLayer = p.getInt("layer", Layers.ANSWER).coerceIn(0, 1)
        state.showCorrection = p.getBoolean("showCorrection", true)
        rightHanded = p.getBoolean("rightHand", false)
        state.pageRotation = sessionRepo.rotation()
        state.volumeFlip = sessionRepo.volumeFlip()
        state.keepAwake = sessionRepo.keepAwake()
        state.followSystemDark = sessionRepo.followSystemDark()
    }

    private fun savePrefs() {
        prefs().edit().apply {
            putInt("color", colorIndex); putInt("width", widthIndex)
            putInt("eraser", eraserIndex); putInt("shape", currentShape)
            putInt("tint", state.tint); putFloat("brightness", state.brightness)
            putBoolean("browse", state.browse)
            putBoolean("browseMigrated", true)
            putBoolean("spread", state.spread)
            putInt("displayMode", state.displayMode)
            putBoolean("modeChosen", modeChosen)
            putBoolean("stylus", state.stylusOnly); putBoolean("palm", state.palmGuard)
            putBoolean("edge", state.edgeFlip); putBoolean("rightHand", rightHanded)
            putInt("layer", state.activeLayer)
            putBoolean("showCorrection", state.showCorrection)
        }.apply()
        sessionRepo.setRotation(state.pageRotation)
        sessionRepo.setVolumeFlip(state.volumeFlip)
        sessionRepo.setKeepAwake(state.keepAwake)
        sessionRepo.setFollowSystemDark(state.followSystemDark)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun flash(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dotDrawable(fill: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (selected) setStroke(dp(2), 0xFF4A4A50.toInt())
    }

    /** 笔画预览：圆角横条的高度就是这一档的实际粗细，5 档一眼分辨。 */
    private fun strokeDrawable(hDp: Int, selected: Boolean, eraser: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(hDp) / 2f
            val on = if (eraser) 0xFFF2A03D.toInt() else 0xFF2F6FED.toInt()
            setColor(if (selected) on else 0xFFA8AEB8.toInt())
            if (selected) setStroke(dp(1), 0x33000000)
        }

    // ----- toolbar -----
    private fun buildDots() {
        colors.forEachIndexed { i, c ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(6)
                }
                contentDescription = getString(R.string.cd_color)
                setOnClickListener {
                    colorIndex = i
                    if (currentTool == Tools.ERASER) selectTool(lastDrawTool)
                    savePrefs(); refreshDots(); refreshTools()
                }
            }
            colorDots.add(v); colorRow.addView(v)
        }
        widths.forEachIndexed { i, _ ->
            val holder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(24)).apply {
                    marginEnd = dp(2)
                }
                contentDescription = getString(R.string.cd_width_step, i + 1)
                isClickable = true
                setOnClickListener {
                    if (currentTool == Tools.ERASER) eraserIndex = i else widthIndex = i
                    savePrefs(); refreshDots(); refreshTools()
                }
            }
            val bar = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(18), dp(BrushSizes.previewDp(i)), Gravity.CENTER)
            }
            holder.addView(bar); widthDots.add(bar); widthRow.addView(holder)
        }
        refreshDots()
    }

    private fun refreshDots() {
        val eraser = currentTool == Tools.ERASER
        colorDots.forEachIndexed { i, v ->
            v.background = dotDrawable(colors[i], i == colorIndex)
            v.alpha = if (eraser) 0.45f else 1f
        }
        val sel = if (eraser) eraserIndex else widthIndex
        widthDots.forEachIndexed { i, v ->
            v.background = strokeDrawable(
                BrushSizes.previewDp(i), i == sel, eraser)
        }
    }

    private fun selectTool(t: Int) {
        // 浏览态点绘图按钮 = 一步切回做题并选中该工具；
        // 原来会悄悄改 currentTool 但 InkView 不收触摸，等于点了没反应。
        if (state.browse) setBrowseMode(false)
        if (t != Tools.ERASER && t != Tools.LASSO && t != Tools.NOTE) lastDrawTool = t
        currentTool = t
        // 顶栏选了别的工具时，悬浮橡皮的切换态自动复位，避免两处状态打架
        if (t != Tools.ERASER) tempEraserActive = false
        if (t != Tools.LASSO) activeViews.values.forEach { it.cancelSelection() }
        refreshTools()
        refreshTempEraser()
    }

    /** 默认浏览；点按钮切到做题做笔记，再点回浏览。偏好持久化。 */
    private fun setBrowseMode(v: Boolean) {
        if (state.browse == v) return
        state.browse = v
        if (v && tempEraserActive) {
            // 浏览态不能擦，先把悬浮橡皮的切换态复位
            tempEraserActive = false
            currentTool = lastDrawTool
        }
        savePrefs()
        adapter?.setBrowseMode(v)
        refreshTools()
        refreshTempEraser()
        updatePagerInput()
    }

    private fun refreshTools() {
        btnPen.isSelected = currentTool == Tools.PEN && !state.browse
        btnHighlight.isSelected = currentTool == Tools.HIGHLIGHT && !state.browse
        btnShape.isSelected = currentTool == Tools.SHAPE && !state.browse
        btnLasso.isSelected = currentTool == Tools.LASSO && !state.browse
        btnNote.isSelected = currentTool == Tools.NOTE && !state.browse
        btnEraser.isSelected = currentTool == Tools.ERASER && !state.browse
        // 语义反转：做题态高亮；图标显示「点它会变成什么」
        btnBrowse.isSelected = !state.browse
        btnBrowse.setImageResource(
            if (state.browse) R.drawable.ic_mode_write
            else R.drawable.ic_mode_browse)
        btnBrowse.contentDescription = getString(
            if (state.browse) R.string.cd_write else R.string.cd_browse)
        val dim = if (state.browse) 0.4f else 1f
        listOf(btnPen, btnHighlight, btnShape, btnLasso, btnNote, btnEraser).forEach {
            it.alpha = dim
        }
        val inDocNow = ::pager.isInitialized && pager.visibility == View.VISIBLE
        shapeBar.visibility =
            if (!barsHidden && inDocNow && currentTool == Tools.SHAPE && !state.browse)
                View.VISIBLE else View.GONE
        selectionBar.visibility =
            if (!barsHidden && inDocNow && !state.browse &&
                activeViews.values.any { it.hasSelection() })
                View.VISIBLE else View.GONE
        mapOf(
            R.id.btnLine to Shapes.LINE, R.id.btnRect to Shapes.RECT,
            R.id.btnOval to Shapes.OVAL, R.id.btnArrow to Shapes.ARROW
        ).forEach { (id, s) -> findViewById<ImageButton>(id).isSelected = currentShape == s }
        refreshDots()
        refreshLayerHint()
        refreshUndoState()
        refreshBrushSlots()
    }

    private fun refreshLayerHint() {
        val v = findViewById<View>(R.id.layerDot)
        v.background = dotDrawable(
            if (state.activeLayer == Layers.CORRECTION) 0xFFF2A03D.toInt()
            else 0xFF2F6FED.toInt(), false
        )
        v.alpha = if (state.showCorrection) 1f else 0.35f
    }

    private fun refreshBookmark() {
        val on = inkStore.bookmarks().contains(currentPage())
        btnBookmark.isSelected = on
        btnBookmark.setColorFilter(
            ContextCompat.getColor(this, if (on) R.color.accent else R.color.icon)
        )
    }

    private fun setupClicks() {
        val open = View.OnClickListener { openDoc.launch(arrayOf("application/pdf")) }
        findViewById<ImageButton>(R.id.btnOpen).setOnClickListener(open)
        findViewById<ImageButton>(R.id.btnOpenEmpty).setOnClickListener(open)
        btnHomeMore.setOnClickListener { showHomeMenu() }

        btnPen.setOnClickListener { selectTool(Tools.PEN) }
        btnHighlight.setOnClickListener { selectTool(Tools.HIGHLIGHT) }
        btnShape.setOnClickListener { selectTool(Tools.SHAPE); refreshTools() }
        btnLasso.setOnClickListener { selectTool(Tools.LASSO); refreshTools() }
        btnNote.setOnClickListener { selectTool(Tools.NOTE); refreshTools() }
        btnEraser.setOnClickListener { selectTool(Tools.ERASER) }
        mapOf(
            R.id.btnLine to Shapes.LINE, R.id.btnRect to Shapes.RECT,
            R.id.btnOval to Shapes.OVAL, R.id.btnArrow to Shapes.ARROW
        ).forEach { (id, s) ->
            findViewById<ImageButton>(id).setOnClickListener {
                currentShape = s; selectTool(Tools.SHAPE); savePrefs(); refreshTools()
            }
        }

        findViewById<ImageButton>(R.id.btnSelDelete).setOnClickListener {
            activeViews[focusKey]?.deleteSelection()
            refreshTools()
        }
        findViewById<ImageButton>(R.id.btnSelCancel).setOnClickListener {
            activeViews[focusKey]?.cancelSelection()
            refreshTools()
        }

        findViewById<ImageButton>(R.id.btnUndo).setOnClickListener { onUndo() }
        findViewById<ImageButton>(R.id.btnRedo).setOnClickListener { onRedo() }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener { showMenu() }

        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { step(-1) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(1) }
        btnBrowse.setOnClickListener { setBrowseMode(!state.browse) }
        btnBookmark.setOnClickListener {
            inkStore.toggleBookmark(currentPage())
            refreshBookmark()
        }
        findViewById<ImageButton>(R.id.btnZoomReset).setOnClickListener {
            adapter?.resetZoom(pager.currentItem)
            currentZoom = 1f
            updatePagerInput()
        }
        btnHide.setOnClickListener { toggleBars(true) }
        btnShow.setOnClickListener { toggleBars(false) }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) pager.setCurrentItem(progress, false)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        attachEdge(edgeLeft, true)
        attachEdge(edgeRight, false)
        seek.contentDescription = getString(R.string.cd_page_seek)
        tvPage.contentDescription = getString(R.string.cd_page)
        // 常驻只留 6 个高频图标，图形 / 套索 / 便签收进浮层
        btnShape.visibility = View.GONE
        btnLasso.visibility = View.GONE
        btnNote.visibility = View.GONE
        refreshTools()
    }

    private fun attachEdge(v: View, left: Boolean) {
        v.setOnTouchListener { _, ev ->
            if (!state.edgeFlip || repo == null) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pager.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_UP -> {
                    step(if (left) -1 else 1)
                    pager.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pager.requestDisallowInterceptTouchEvent(false); true
                }
                else -> true
            }
        }
    }

    private fun setupInsets() {
        // Android 9 / EMUI 9.x：显式声明是否延伸到刘海区，行为才可预测。
        // 用户在系统里开了「隐藏显示区域」就不再延伸，也不再补边，避免双份留白。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val mode = if (NotchCompat.huaweiNotchHidden(this)) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = mode
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val q = NotchCompat.safeInsets(this, ins)
            // 状态栏 / 导航栏已彻底隐藏（hideSystemBars），不再为它们留白；
            // 刘海与物理安全区仍由 NotchCompat 提供，避免压住正文。
            sysTop = 0
            sysBottom = 0
            applySafeInsets(q[0], q[1], q[2], q[3])
            // 只在系统又把栏露出来时重 hide，避免 hide 自身触发的 insets
            // dispatch 形成重复调用
            if (ins.isVisible(WindowInsetsCompat.Type.systemBars())) hideSystemBars()
            ins
        }
    }

    /** 工具条、底部条、左右翻页热区与展开按钮一起平移出刘海区。 */
    private fun applySafeInsets(l: Int, t: Int, r: Int, b: Int) {
        safeL = l
        safeT = t
        safeR = r
        safeB = b
        applyChrome()
    }

    private fun applyOrientationLayout() {
        state.landscape = resources.configuration.orientation !=
            Configuration.ORIENTATION_PORTRAIT
        applyChrome()
        hideSystemBars()
    }

    /**
     * 横竖屏唯一的布局入口：栏高必须把状态栏 / 刘海算进去，否则固定的 50dp
     * 会被 paddingTop 吃掉、图标被裁；pager、浮动条、悬浮键全部同步。
     */
    private fun applyChrome() {
        val portrait = !state.landscape
        // systemBars 在部分 ROM 上已包含刘海高度，取 max 避免补两次
        val cutTop = maxOf(sysTop, safeT)
        val cutBottom = maxOf(sysBottom, safeB)
        val baseTop = if (portrait) dp(50) else dp(54)
        val baseBottom = if (portrait) dp(48) else dp(52)
        // P0：栏隐藏后必须把 padding 一起归零。旧版一次性写死 54/52dp，
        // 栏 GONE 了那 140dp 还占着，就变成截图里的上下大片空白。
        val barTop = if (barsHidden) 0 else baseTop + cutTop + dp(6)
        val barBottom = if (barsHidden) 0 else baseBottom + cutBottom + dp(4)

        val lpTop = topBar.layoutParams as FrameLayout.LayoutParams
        lpTop.height = barTop
        topBar.layoutParams = lpTop
        topBar.setPadding(safeL + dp(8), cutTop + dp(6), safeR + dp(8), dp(6))

        val lpBottom = bottomBar.layoutParams as FrameLayout.LayoutParams
        lpBottom.height = barBottom
        bottomBar.layoutParams = lpBottom
        bottomBar.setPadding(safeL + dp(8), dp(4), safeR + dp(8), cutBottom + dp(4))

        // 横屏左上角可能是刘海（荣耀 10 90°），左右按物理安全区避让；
        // 系统开了「隐藏显示区域」时 safeL/safeR=0，就是真正铺满。
        val padL = if (portrait) 0 else safeL
        val padR = if (portrait) 0 else safeR
        pager.setPadding(padL, barTop, padR, barBottom)
        val inDoc = repo != null && pager.visibility == View.VISIBLE
        val barVis = if (barsHidden || !inDoc) View.GONE else View.VISIBLE
        topBar.visibility = barVis
        bottomBar.visibility = barVis
        btnShow.visibility =
            if (barsHidden && inDoc) View.VISIBLE else View.GONE
        // v11：barsHidden 不参与，隐藏栏后右下角仍常驻橡皮
        refreshTempEraser()
        emptyState.setPadding(safeL, 0, safeR, safeB)
        val ml = btnHomeMore.layoutParams as FrameLayout.LayoutParams
        ml.topMargin = cutTop + dp(8)
        ml.marginEnd = safeR + dp(10)
        btnHomeMore.layoutParams = ml
        for (v in listOf(shapeBar, selectionBar)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = barTop + dp(2)
            v.layoutParams = lp
        }

        for (v in listOf(edgeLeft, edgeRight)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = if (portrait) dp(10) else dp(14)
            v.layoutParams = lp
        }
        edgeLeft.translationX = safeL.toFloat()
        edgeRight.translationX = -safeR.toFloat()

        // 竖屏常驻只留 6 项；被收起的全部搬进浮层，不靠横向滚动凑合
        val layerDot = findViewById<View>(R.id.layerDot)
        val bHighlight = btnHighlight
        val bRedo = findViewById<ImageButton>(R.id.btnRedo)
        widthRow.visibility = if (portrait) View.GONE else View.VISIBLE
        brushRow.visibility = if (portrait) View.GONE else View.VISIBLE
        layerDot.visibility = if (portrait) View.GONE else View.VISIBLE
        bHighlight.visibility = if (portrait) View.GONE else View.VISIBLE
        bRedo.visibility = if (portrait) View.GONE else View.VISIBLE
        for (i in 0 until toolsStrip.childCount) {
            val c = toolsStrip.getChildAt(i)
            if (c !is ImageButton && c !is LinearLayout &&
                c !is HorizontalScrollView && c.id != R.id.layerDot &&
                c.layoutParams.width > 0 && c.layoutParams.width <= dp(2)
            ) {
                c.visibility = if (portrait) View.GONE else View.VISIBLE
            }
        }

        // 5 档后压缩 holder 与间距：横屏工具条只比 3 档多约 30dp
        val dot = if (portrait) dp(18) else dp(20)
        colorDots.forEach { v ->
            val p = v.layoutParams as LinearLayout.LayoutParams
            p.width = dot
            p.height = dot
            p.marginEnd = if (portrait) dp(4) else dp(6)
            v.layoutParams = p
        }
        for (i in 0 until widthRow.childCount) {
            val h = widthRow.getChildAt(i)
            val p = h.layoutParams as LinearLayout.LayoutParams
            val d = if (portrait) dp(20) else dp(22)
            p.width = d
            p.height = dp(24)
            p.marginEnd = dp(2)
            h.layoutParams = p
        }
        brushSlots.forEach { v ->
            val p = v.layoutParams as LinearLayout.LayoutParams
            val d = if (portrait) dp(12) else dp(13)
            p.width = d
            p.height = d
            p.marginEnd = if (portrait) dp(3) else dp(4)
            v.layoutParams = p
        }

        // v11 悬浮橡皮：隐藏栏时统一右下角，与左下角 btnShow 左右对称。
        // cutBottom 已是 max(系统栏, 刘海下边)，+12dp 保证不贴底、不压刘海。
        // 栏显示时维持原位置（竖屏底栏上方 / 横屏右侧中部留白）。
        val fl = btnTempEraser.layoutParams as FrameLayout.LayoutParams
        fl.topMargin = 0
        if (barsHidden) {
            fl.gravity = Gravity.END or Gravity.BOTTOM
            fl.bottomMargin = cutBottom + dp(12)
            fl.marginEnd = safeR + dp(12)
        } else if (portrait) {
            fl.gravity = Gravity.END or Gravity.BOTTOM
            fl.bottomMargin = barBottom + dp(10)
            fl.marginEnd = dp(12)
        } else {
            fl.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            fl.bottomMargin = 0
            fl.marginEnd = dp(18)
        }
        btnTempEraser.layoutParams = fl

        val sl = btnShow.layoutParams as FrameLayout.LayoutParams
        sl.bottomMargin =
            if (barsHidden) cutBottom + dp(12) else barBottom + dp(8)
        btnShow.layoutParams = sl
        btnShow.translationX = safeL.toFloat()

        refreshTools()
    }

    /** 竖屏浮层里的调色 / 粗细 / 笔刷槽，功能与主条完全一致。 */
    private fun buildMenuInkRow(row: LinearLayout) {
        row.removeAllViews()
        val items = ArrayList<Triple<View, String, Int>>()
        fun refresh() {
            for ((v, kind, i) in items) {
                when (kind) {
                    "c" -> {
                        v.background = dotDrawable(colors[i], i == colorIndex)
                        v.alpha = if (currentTool == Tools.ERASER) 0.45f else 1f
                    }
                    "w" -> v.background = strokeDrawable(
                        BrushSizes.previewDp(i),
                        i == if (currentTool == Tools.ERASER) eraserIndex else widthIndex,
                        currentTool == Tools.ERASER
                    )
                    "b" -> {
                        val b = sessionRepo.brush(i)
                        v.background = dotDrawable(
                            if (b == null) 0x22000000
                            else colors[b.color.coerceIn(0, colors.lastIndex)],
                            b != null
                        )
                    }
                }
            }
        }
        fun add(v: View, kind: String, i: Int) {
            row.addView(v)
            items.add(Triple(v, kind, i))
        }
        colors.forEachIndexed { i, _ ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(3)
                }
                contentDescription = getString(R.string.cd_color)
                setOnClickListener {
                    colorIndex = i
                    if (currentTool == Tools.ERASER) selectTool(lastDrawTool)
                    savePrefs()
                    refreshDots()
                    refreshTools()
                    refresh()
                }
            }
            add(v, "c", i)
        }
        // 5 色 + 5 档 + 分隔 + 4 笔刷槽 ≈ 258dp，276dp 浮层一屏放得下
        for (i in 0 until widths.size + 1) {
            if (i < widths.size) {
                val idx = i
                val holder = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(22)).apply {
                        marginEnd = dp(2)
                    }
                    contentDescription = getString(R.string.cd_width_step, i + 1)
                    isClickable = true
                    setOnClickListener {
                        if (currentTool == Tools.ERASER) eraserIndex = idx
                        else widthIndex = idx
                        savePrefs()
                        refreshDots()
                        refreshTools()
                        refresh()
                    }
                }
                val bar = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        dp(16), dp(BrushSizes.previewDp(i)), Gravity.CENTER)
                }
                holder.addView(bar)
                row.addView(holder)
                items.add(Triple(bar, "w", i))
            } else {
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    }
                    setBackgroundColor(ContextCompat.getColor(
                        this@MainActivity, R.color.divider))
                }
                row.addView(sep)
            }
        }
        for (i in 0 until 4) {
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginEnd = dp(2)
                }
                contentDescription = getString(R.string.cd_brush_slot)
                val idx = i
                setOnClickListener { applyBrushSlot(idx); refresh() }
                setOnLongClickListener {
                    storeBrushSlot(idx)
                    refresh()
                    flash("✓")
                    true
                }
            }
            add(v, "b", i)
        }
        refresh()
    }

    private fun applyHandedness() {
        val g1 = if (rightHanded) Gravity.END or Gravity.CENTER_VERTICAL
        else Gravity.START or Gravity.CENTER_VERTICAL
        val g2 = if (rightHanded) Gravity.START or Gravity.CENTER_VERTICAL
        else Gravity.END or Gravity.CENTER_VERTICAL
        val lp = toolsScroll.layoutParams as FrameLayout.LayoutParams
        lp.gravity = g1
        lp.marginStart = if (rightHanded) dp(46) else 0
        lp.marginEnd = if (rightHanded) 0 else dp(46)
        toolsScroll.layoutParams = lp
        val lp2 = btnHide.layoutParams as FrameLayout.LayoutParams
        lp2.gravity = g2; btnHide.layoutParams = lp2
    }

    /** 双页模式下每个 item 是一组两页，itemCount 减半。 */
    private fun viewCount(): Int = DisplayMode.viewCount(
        state.displayMode, state.landscape, repo?.count ?: 1)

    /** 当前显示的「逻辑页」（书签 / 导出 / 会话 / 页码都用它）。 */
    private fun currentPage(): Int = DisplayMode.logical(
        state.displayMode, state.landscape, pager.currentItem)
        .coerceIn(0, ((repo?.count ?: 1) - 1).coerceAtLeast(0))

    private fun viewPosOf(page: Int): Int = DisplayMode.viewPos(
        state.displayMode, state.landscape, page)
        .coerceIn(0, (viewCount() - 1).coerceAtLeast(0))

    private fun goToPage(page: Int, smooth: Boolean = false) {
        pager.setCurrentItem(viewPosOf(page), smooth)
    }

    private fun updatePagerInput(pos: Int = pager.currentItem) {
        // currentZoom 是全局值、切页不重置；按当前页真实倍率判断，
        // 否则放大后翻到下一页仍会误禁横向翻页。
        val z = adapter?.zoomOf(pos) ?: 1f
        pager.isUserInputEnabled = state.browse && z <= 1.01f
    }

    private fun step(delta: Int) {
        if (repo == null) return
        val n = viewCount()
        if (n <= 0) return
        pager.setCurrentItem((pager.currentItem + delta).coerceIn(0, n - 1), true)
    }

    private fun updatePage(pos: Int) {
        seek.progress = pos
        tvPage.text = DisplayMode.label(
            state.displayMode, state.landscape, pos, repo?.count ?: 1)
    }

    /** 栏的显隐统一走 applyChrome，保证 padding 与 visibility 永远一致。 */
    private fun toggleBars(hide: Boolean) {
        if (barsHidden == hide) return
        barsHidden = hide
        applyChrome()
        refreshPageLayout()
    }

    /** 可用尺寸变了，必须按新尺寸重渲染，否则纸页变大后仍是旧的低清位图。 */
    private fun refreshPageLayout() {
        adapter?.invalidateRenderCache()
        adapter?.notifyDataSetChanged()
    }

    /** 把当前显示模式推给 adapter 并重渲染。竖屏默认按宽度适配。 */
    private fun applyPageMode() {
        val mode = state.effectiveMode
        adapter?.let {
            it.fitMode = if (mode == DisplayMode.WIDTH)
                PageContainer.WIDTH else PageContainer.FIT
            it.doublePage = mode == DisplayMode.DOUBLE
        }
        refreshPageLayout()
        updatePagerInput()
    }

    private fun setDisplayMode(mode: Int) {
        if (state.displayMode == mode) return
        // itemCount 变化时先记住逻辑页，切完再按新映射跳回去
        val keepPage = if (repo != null) currentPage() else 0
        state.displayMode = mode
        modeChosen = true
        savePrefs()
        applyPageMode()
        if (repo != null) goToPage(keepPage)
        saveSessionNow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (barsHidden && ev.pointerCount == 3 &&
            ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTriTap < 420L) {
                toggleBars(false); lastTriTap = 0L
            } else lastTriTap = now
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showHome() {
        pager.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        btnShow.visibility = View.GONE
        shapeBar.visibility = View.GONE
        selectionBar.visibility = View.GONE
        refreshTempEraser()
        edgeLeft.visibility = View.GONE
        edgeRight.visibility = View.GONE
        emptyState.setPadding(safeL, 0, safeR, safeB)
        renderHomeShelf()
    }

    /**
     * 无记录 = 完全就是图二：圆盘占满全屏、严格居中，没有任何其它元素。
     * 有记录 = 圆盘仍可点（重新导入新 PDF）+ 下方常驻浏览记录。
     */
    private fun renderHomeShelf() {
        homeShelf.removeAllViews()
        val items = recents.all()
        val stage = homeStage.layoutParams as LinearLayout.LayoutParams
        val scroll = homeScroll.layoutParams as LinearLayout.LayoutParams
        if (items.isEmpty()) {
            homeScroll.visibility = View.GONE
            btnHomeMore.visibility = View.GONE
            stage.weight = 1f
            scroll.weight = 0f
        } else {
            homeScroll.visibility = View.VISIBLE
            btnHomeMore.visibility = View.VISIBLE
            stage.weight = 0.56f
            scroll.weight = 0.44f
        }
        homeStage.layoutParams = stage
        homeScroll.layoutParams = scroll

        for (item in items) {
            val u = Uri.parse(item.uri)
            val card = layoutInflater.inflate(
                R.layout.item_home_card, homeShelf, false)

            // 标题与文件管理器一模一样：系统 DISPLAY_NAME，保留 .pdf、不截断。
            // 手动改过名的记录保持用户命名；老记录（升级前剥过后缀）自动刷新。
            val real = recents.displayName(u)
            val looksReal = real.contains('.') && !real.all { it.isDigit() }
            val title = when {
                item.custom -> item.name
                looksReal -> real
                else -> item.name
            }
            if (!item.custom && looksReal && real != item.name) {
                recents.refreshName(item.fp, real)
            }
            card.findViewById<TextView>(R.id.cardName).text = title

            val s = sessionRepo.session(item.fp)
            val cur = (s.page + 1).coerceAtLeast(1)
            card.findViewById<TextView>(R.id.cardProgress).text =
                if (item.pages > 0) "$cur / ${item.pages}" else "第 ${cur} 页"
            card.findViewById<View>(R.id.cardInkDot).visibility =
                if (InkStore.hasAnyInk(this, item.fp)) View.VISIBLE else View.GONE

            // 点开 = 打开这份 PDF，并按内容指纹恢复页码 / 缩放 / 工具 / 模式 / 平移
            card.setOnClickListener {
                if (openUri(u)) {
                    prefs().edit().putString("uri", item.uri).apply()
                    recents.touch(item.fp, u, repo?.count ?: item.pages)
                } else flash("!")
            }
            card.setOnLongClickListener {
                showShelfActions(item) { renderHomeShelf() }
                true
            }
            homeShelf.addView(card)
        }
    }

    /** 首页「更多」：只放不依赖当前卷子的全局动作，避免点了没反应。 */
    private fun showHomeMenu() {
        val grid = GridLayout(this).apply {
            columnCount = 4
            background = ContextCompat.getDrawable(
                this@MainActivity, R.drawable.popup_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val popup = PopupWindow(grid, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.icon)
        for (a in homeMenuActions()) {
            val b = layoutInflater.inflate(R.layout.item_menu, grid, false) as ImageButton
            b.layoutParams = GridLayout.LayoutParams().apply {
                width = dp(52); height = dp(52)
            }
            b.setImageResource(a.icon)
            b.setColorFilter(if (a.active()) on else off)
            b.contentDescription = a.label
            b.setOnClickListener { popup.dismiss(); a.run() }
            b.setOnLongClickListener { flash(a.label); true }
            grid.addView(b)
        }
        popup.showAsDropDown(btnHomeMore, 0, dp(6))
    }

    private fun homeMenuActions() = listOf(
        MenuAction(R.drawable.ic_backup, getString(R.string.cd_backup), { false }) {
            createBackup.launch("moye-backup.moye")
        },
        MenuAction(R.drawable.ic_restore, getString(R.string.cd_restore), { false }) {
            openBackup.launch(arrayOf("*/*"))
        },
        MenuAction(R.drawable.ic_hand, getString(R.string.cd_hand), { rightHanded }) {
            rightHanded = !rightHanded
            savePrefs(); applyHandedness()
        },
        MenuAction(R.drawable.ic_awake, getString(R.string.cd_awake), { state.keepAwake }) {
            state.keepAwake = !state.keepAwake
            sessionRepo.setKeepAwake(state.keepAwake)
            savePrefs(); applyAwake()
        },
        MenuAction(R.drawable.ic_dark, getString(R.string.cd_dark), {
            state.followSystemDark
        }) {
            state.followSystemDark = !state.followSystemDark
            sessionRepo.setFollowSystemDark(state.followSystemDark)
            savePrefs(); applySystemDark(null)
        },
        MenuAction(R.drawable.ic_stylus, getString(R.string.cd_stylus), {
            state.stylusOnly
        }) {
            state.stylusOnly = !state.stylusOnly; savePrefs()
        }
    )

    private fun openUri(uri: Uri): Boolean {
        val r = try { PdfRepo(this, uri) } catch (e: Exception) {
            showHome(); return false
        }
        if (r.count <= 0) { r.close(); showHome(); return false }
        repo?.close()
        repo = r
        TileCache.clear()
        vm.currentFp = inkStore.attach(uri)
        activeViews.clear()
        history.clear()
        refreshUndoState()

        val session = sessionRepo.session(vm.currentFp)
        state.activeLayer = session.layer.coerceIn(0, 1)
        state.tint = session.tint.coerceIn(0, 2)
        state.brightness = session.brightness.coerceIn(-1f, 1f)
        state.pageRotation = session.rotation.coerceIn(0, 270)
        // 横版 PDF 在竖屏时自动旋转 90°，避免横向内容缩得过窄。
        // 结果写进会话，用户之后在「更多」里手动改旋转会覆盖它。
        if (!state.landscape && session.rotation == 0 &&
            r.ratio(0) > 1.2f) {
            state.pageRotation = 90
        }
        state.showCorrection = session.showCorrection
        currentTool = session.brushTool.coerceIn(0, Tools.NOTE)
        currentZoom = session.zoom.coerceIn(1f, 4f)
        state.displayMode = session.displayMode
        // 升级前存的 FIT 不该让横屏一直居中一条；用户没手动切过就强制铺满
        if (!modeChosen && state.landscape) state.displayMode = DisplayMode.WIDTH
        currentPanX = session.panX
        currentPanY = session.panY

        val ad = PagesAdapter(
            r, this, state, workScope,
            { k, v -> activeViews[k] = v },
            { k -> activeViews.remove(k) },
            { s -> currentZoom = s; updatePagerInput() },
            { d -> step(d) }
        )
        ad.initialZoom = currentZoom
        ad.initialPanX = currentPanX
        ad.initialPanY = currentPanY
        ad.fitMode = if (state.effectiveMode == DisplayMode.WIDTH)
            PageContainer.WIDTH else PageContainer.FIT
        ad.doublePage = state.effectiveMode == DisplayMode.DOUBLE
        ad.onPanChange = { px, py -> currentPanX = px; currentPanY = py }
        ad.setBrowseMode(state.browse)
        adapter = ad
        pager.adapter = ad
        pager.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        topBar.visibility = if (barsHidden) View.GONE else View.VISIBLE
        bottomBar.visibility = if (barsHidden) View.GONE else View.VISIBLE
        btnShow.visibility = if (barsHidden) View.VISIBLE else View.GONE
        seek.max = (viewCount() - 1).coerceAtLeast(0)
        // createdPage 只用于「重建后恢复当前这一份」，用完立刻清；
        // 否则之后从首页点开任何卷子都会被带到上一份的旧页码。
        val restored = if (vm.createdPage >= 0) {
            val p = vm.createdPage
            vm.createdPage = -1
            p
        } else session.page
        val logical = restored.coerceIn(0, r.count - 1)
        val page = viewPosOf(logical)
        ad.restorePanAt = page
        pager.setCurrentItem(page, false)
        focusKey = "p$logical"
        updatePage(page)
        pager.post { activeViews.values.forEach { it.setPageLook(state.tint) } }
        refreshBookmark()
        refreshTempEraser()
        refreshTools()
        updatePagerInput()
        return true
    }

    // ----- 全局时间轴 -----
    private fun onUndo() = applyChanges(history.undo(), false)
    private fun onRedo() = applyChanges(history.redo(), true)

    private fun applyChanges(changes: List<InkHistory.Change>?, forward: Boolean) {
        if (changes == null) return
        for (c in changes) {
            val target = if (forward) c.after else c.before
            val v = activeViews[c.key]
            if (v != null) v.restoreLocal(target)
            else inkStore.saveNow(c.key, target) // 页已回收，直接落盘，下次 attach 自然恢复
        }
        refreshUndoState()
    }

    private fun clearAllWithUndo() {
        activeViews.values.forEach { it.flush() }
        val snap = inkStore.snapshot()
        if (snap.isEmpty()) return
        snapshots.save(vm.currentFp, snap)
        history.push(snap.map { (k, v) -> InkHistory.Change(k, v, emptyList()) })
        inkStore.clearAll()
        activeViews.values.forEach { it.clearLocal() }
        refreshUndoState()
    }

    private fun refreshInksFromStore() {
        for ((k, v) in activeViews) v.restoreLocal(inkStore.load(k))
    }

    // ----- 浮层菜单 -----
    private fun menuActions(): List<MenuAction> = listOf(
        MenuAction(R.drawable.ic_close, getString(R.string.cd_clear), { false }) {
            activeViews[focusKey]?.clearPage()
        },
        MenuAction(R.drawable.ic_trash, getString(R.string.cd_clear_all), { false }) {
            confirmClearAll()
        },
        MenuAction(R.drawable.ic_export, getString(R.string.cd_export), { false }) {
            exportCurrentPng()
        },
        MenuAction(R.drawable.ic_pdf, getString(R.string.cd_pdf_all), { false }) {
            val r = repo
            if (r != null) exportPdf((0 until r.count).toList(), "全本")
        },
        MenuAction(R.drawable.ic_pdf, getString(R.string.cd_pdf_page), { false }) {
            // pages() 收 display position，返回的正是逻辑页列表
            val vp = pager.currentItem
            exportPdf(DisplayMode.pages(state.displayMode, state.landscape, vp,
                repo?.count ?: 1).toList(), "单页")
        },
        MenuAction(R.drawable.ic_backup, getString(R.string.cd_backup), { false }) {
            createBackup.launch("moye-backup.moye")
        },
        MenuAction(R.drawable.ic_restore, getString(R.string.cd_restore), { false }) {
            openBackup.launch(arrayOf("*/*"))
        },
        MenuAction(R.drawable.ic_grid, getString(R.string.cd_overview), { false }) {
            showOverview()
        },
        MenuAction(R.drawable.ic_nav, getString(R.string.cd_nav), { false }) {
            showNav()
        },
        MenuAction(R.drawable.ic_eye, getString(R.string.cd_tint), { state.tint != 0 }) {
            state.tint = (state.tint + 1) % 3
            savePrefs()
            activeViews.values.forEach { it.setPageLook(state.tint) }
            adapter?.invalidateRenderCache()
            adapter?.notifyDataSetChanged()
            saveSessionNow()
        },
        MenuAction(R.drawable.ic_spread, getString(R.string.cd_spread), { state.spread }) {
            state.spread = !state.spread
            savePrefs()
            adapter?.invalidateRenderCache()
            adapter?.notifyDataSetChanged()
            saveSessionNow()
        },
        MenuAction(R.drawable.ic_layers, getString(R.string.cd_layer), {
            state.activeLayer == Layers.CORRECTION
        }) {
            state.activeLayer = if (state.activeLayer == Layers.ANSWER)
                Layers.CORRECTION else Layers.ANSWER
            savePrefs(); refreshTools()
        },
        MenuAction(R.drawable.ic_eye_off, getString(R.string.cd_hide_layer), {
            !state.showCorrection
        }) {
            state.showCorrection = !state.showCorrection
            savePrefs()
            refreshInksFromStore()
            adapter?.notifyDataSetChanged()
            refreshTools()
        },
        MenuAction(R.drawable.ic_hand, getString(R.string.cd_hand), { rightHanded }) {
            rightHanded = !rightHanded
            savePrefs(); applyHandedness()
        },
        MenuAction(R.drawable.ic_stylus, getString(R.string.cd_stylus), { state.stylusOnly }) {
            state.stylusOnly = !state.stylusOnly; savePrefs()
        },
        MenuAction(R.drawable.ic_palm, getString(R.string.cd_palm), { state.palmGuard }) {
            state.palmGuard = !state.palmGuard; savePrefs()
        },
        MenuAction(R.drawable.ic_edge, getString(R.string.cd_edge), { state.edgeFlip }) {
            state.edgeFlip = !state.edgeFlip; savePrefs()
        },
        MenuAction(R.drawable.ic_rotate, getString(R.string.cd_rotate), {
            state.pageRotation != 0
        }) {
            rotatePage()
        },
        MenuAction(R.drawable.ic_volume, getString(R.string.cd_volume), {
            state.volumeFlip
        }) {
            state.volumeFlip = !state.volumeFlip
            sessionRepo.setVolumeFlip(state.volumeFlip)
            savePrefs()
        },
        MenuAction(R.drawable.ic_awake, getString(R.string.cd_awake), {
            state.keepAwake
        }) {
            state.keepAwake = !state.keepAwake
            sessionRepo.setKeepAwake(state.keepAwake)
            savePrefs()
            applyAwake()
        },
        MenuAction(R.drawable.ic_dark, getString(R.string.cd_dark), {
            state.followSystemDark
        }) {
            state.followSystemDark = !state.followSystemDark
            sessionRepo.setFollowSystemDark(state.followSystemDark)
            savePrefs()
            applySystemDark(null)
        },
        MenuAction(R.drawable.ic_history, getString(R.string.cd_snapshots), { false }) {
            showSnapshots()
        },
        MenuAction(R.drawable.ic_temp_eraser, getString(R.string.cd_temp_eraser), {
            state.tempEraserOn
        }) {
            state.tempEraserOn = !state.tempEraserOn
            if (!state.tempEraserOn && tempEraserActive) {
                tempEraserActive = false
                selectTool(toolBeforeEraser)
            }
            refreshTempEraser()
        },
        MenuAction(R.drawable.ic_home, getString(R.string.cd_home), { false }) {
            showHome()
        }
    ) + if (state.landscape) listOf(
        MenuAction(R.drawable.ic_fit_screen, getString(R.string.cd_fit_screen), {
            state.effectiveMode == DisplayMode.FIT
        }) { setDisplayMode(DisplayMode.FIT) },
        MenuAction(R.drawable.ic_fit_width, getString(R.string.cd_fit_width), {
            state.effectiveMode == DisplayMode.WIDTH
        }) { setDisplayMode(DisplayMode.WIDTH) },
        MenuAction(R.drawable.ic_double_page, getString(R.string.cd_double_page), {
            state.effectiveMode == DisplayMode.DOUBLE
        }) { setDisplayMode(DisplayMode.DOUBLE) }
    ) else emptyList<MenuAction>()

    private fun showMenu() {
        val pv = layoutInflater.inflate(R.layout.popup_menu, null)
        val grid = pv.findViewById<GridLayout>(R.id.menuGrid)
        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.icon)
        val popup = PopupWindow(pv, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val tr = pv.findViewById<LinearLayout>(R.id.menuToolRow)
        mapOf(
            R.id.menuShape to Tools.SHAPE,
            R.id.menuLasso to Tools.LASSO,
            R.id.menuNote to Tools.NOTE,
            R.id.menuHighlight to Tools.HIGHLIGHT
        ).forEach { (id, t) ->
            val b = tr.findViewById<ImageButton>(id)
            b.isSelected = currentTool == t
            b.setOnClickListener {
                popup.dismiss()
                selectTool(t)
                refreshTools()
            }
            b.setOnLongClickListener {
                flash(b.contentDescription?.toString() ?: "")
                true
            }
        }
        val mRedo = tr.findViewById<ImageButton>(R.id.menuRedo)
        mRedo.alpha = if (history.canRedo) 1f else 0.32f
        mRedo.setOnClickListener { popup.dismiss(); onRedo() }
        val inkWrap = pv.findViewById<View>(R.id.menuInkWrap)
        if (state.landscape) {
            inkWrap.visibility = View.GONE
        } else {
            inkWrap.visibility = View.VISIBLE
            buildMenuInkRow(pv.findViewById<LinearLayout>(R.id.menuInkRow))
        }
        for (a in menuActions()) {
            val b = layoutInflater.inflate(R.layout.item_menu, grid, false) as ImageButton
            val lp = GridLayout.LayoutParams().apply { width = dp(52); height = dp(52) }
            b.setImageResource(a.icon)
            b.setColorFilter(if (a.active()) on else off)
            b.contentDescription = a.label
            b.setOnClickListener {
                popup.dismiss()
                a.run()
                b.setColorFilter(if (a.active()) on else off)
            }
            b.setOnLongClickListener { flash(a.label); true }
            grid.addView(b, lp)
        }
        val bar = pv.findViewById<SeekBar>(R.id.brightnessBar)
        bar.progress = ((state.brightness + 1f) * 50f).toInt().coerceIn(0, 100)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                state.brightness = p / 50f - 1f
                if (fromUser) adapter?.previewBrightness(state.brightness)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                savePrefs()
                adapter?.clearPreview()
                adapter?.invalidateRenderCache()
                adapter?.notifyDataSetChanged()
                saveSessionNow()
            }
        })
        popup.showAsDropDown(findViewById(R.id.btnMore), 0, dp(6))
    }

    // ----- 总览 / 选页导出 -----
    private fun showOverview() {
        val r = repo ?: return
        val v = layoutInflater.inflate(R.layout.dialog_overview, null)
        val rv = v.findViewById<RecyclerView>(R.id.thumbRecycler)
        rv.layoutManager = GridLayoutManager(this, 5)
        val dialog = AlertDialog.Builder(this).setView(v).create()
        val btnExport = v.findViewById<ImageButton>(R.id.btnExportSelected)
        val btnAll = v.findViewById<ImageButton>(R.id.btnSelectAll)
        var oa: OverviewAdapter? = null
        oa = OverviewAdapter(r, inkStore, inkStore.bookmarks(), workScope,
            { p -> goToPage(p); dialog.dismiss() }
        ) { n ->
            btnExport.visibility = if (n > 0) View.VISIBLE else View.GONE
            btnAll.visibility = if (oa?.selectionMode == true) View.VISIBLE else View.GONE
        }
        rv.adapter = oa
        btnAll.setOnClickListener { oa?.selectAll() }
        btnExport.setOnClickListener {
            val pages = oa?.selected?.sorted() ?: emptyList()
            if (pages.isNotEmpty()) {
                exportPdf(pages, "选页")
                dialog.dismiss()
            }
        }
        v.findViewById<ImageButton>(R.id.btnCloseOverview).setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ----- 导航：页码 / 书签目录 / 书架 -----
    private fun showNav() {
        val r = repo ?: return
        val v = layoutInflater.inflate(R.layout.dialog_nav, null)
        val dialog = AlertDialog.Builder(this).setView(v).create()
        val et = v.findViewById<EditText>(R.id.navPage)
        et.hint = (currentPage() + 1).toString()
        fun go() {
            val n = et.text.toString().toIntOrNull() ?: return
            goToPage((n - 1).coerceIn(0, r.count - 1))
            dialog.dismiss()
        }
        et.imeOptions = EditorInfo.IME_ACTION_GO
        et.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { go(); true } else false
        }
        v.findViewById<ImageButton>(R.id.navGo).setOnClickListener { go() }
        v.findViewById<ImageButton>(R.id.btnNextInk).setOnClickListener {
            jumpToNextInk()
            dialog.dismiss()
        }
        v.findViewById<ImageButton>(R.id.btnNextBookmark).setOnClickListener {
            jumpToNextBookmark()
            dialog.dismiss()
        }
        val bmRow = v.findViewById<LinearLayout>(R.id.navBookmarks)
        inkStore.bookmarks().sorted().forEach { p ->
            val t = TextView(this).apply {
                text = (p + 1).toString()
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0x142F6FED)
                    setStroke(dp(1), 0x442F6FED.toInt())
                }
                setOnClickListener {
                    goToPage(p)
                    dialog.dismiss()
                }
            }
            bmRow.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        val shelf = v.findViewById<LinearLayout>(R.id.navShelf)
        recents.all().forEach { item ->
            val t = TextView(this).apply {
                text = item.name
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icon))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    val u = Uri.parse(item.uri)
                    if (openUri(u)) {
                        prefs().edit().putString("uri", item.uri).apply()
                        recents.touch(item.fp, u, repo?.count ?: item.pages)
                    } else flash("!")
                    dialog.dismiss()
                }
                setOnLongClickListener {
                    showShelfActions(item)
                    true
                }
            }
            shelf.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        v.findViewById<ImageButton>(R.id.navClose).setOnClickListener { dialog.dismiss() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog.show()
        dialog.window?.setLayout(dp(480), dp(420))
    }

    // ----- 导出 -----
    /** 按可用堆分档，麒麟 970 / 6GB 上避免一次合成过大的 ARGB_8888 位图。 */
    private fun baseRenderWidth(): Int {
        val mem = Runtime.getRuntime().maxMemory()
        // 屏幕走 1:1 分块，导出整页按堆分档，尽量接近显示宽度又不 OOM
        return when {
            mem < 128L * 1024 * 1024 -> 1200
            mem < 256L * 1024 * 1024 -> 1800
            else -> 2400
        }
    }

    private fun pageSize(page: Int): Pair<Int, Int> {
        val r = repo ?: return 900 to 1273
        val ratio = r.ratioWithRotation(page, state.pageRotation).coerceIn(0.3f, 3f)
        var w = when {
            Runtime.getRuntime().maxMemory() < 128L * 1024 * 1024 -> 1200
            Runtime.getRuntime().maxMemory() < 256L * 1024 * 1024 -> 1800
            else -> 2400
        }
        if (state.effectiveSpread) w = (w * 0.72f).toInt().coerceAtLeast(640)
        var h = (w / ratio).toInt()
        val maxH = (w * 2.2f).toInt().coerceAtMost(3400)
        if (h > maxH) { h = maxH; w = (h * ratio).toInt() }
        return w to h
    }

    private fun exportPdf(pages: List<Int>, tag: String) {
        if (pages.isEmpty()) return
        pendingPdfPages = pages
        val scale = when {
            pages.size > 40 -> 0.72f
            pages.size > 20 -> 0.85f
            else -> 1f
        }
        var w = (baseRenderWidth() * scale).toInt().coerceAtLeast(560)
        if (state.effectiveSpread) w = (w * 0.72f).toInt().coerceAtLeast(560)
        pendingPdfWidth = w
        createPdf.launch("MoYe_${tag}_${System.currentTimeMillis()}.pdf")
    }

    /** Android 9 及以下先拿权限，授权后自动续跑；Android 10+ 直接导出。 */
    private fun exportCurrentPng() {
        val action = { exportCurrentPngInternal() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingExport = action
        requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** 双页并排模式下，单页导出也给出两页，和屏幕上看到的一致。 */
    private fun composeTwoPages(r: PdfRepo, p: Int, w: Int, h: Int): Bitmap {
        val a = PageComposer.compose(r, inkStore, p, w, h, state.tint,
            state.brightness, state.visibleLayers, false, state.pageRotation)
        val b = PageComposer.compose(r, inkStore, p + 1, w, h, state.tint,
            state.brightness, state.visibleLayers, false, state.pageRotation)
        val gap = (w * 0.03f).toInt()
        val out = Bitmap.createBitmap(w * 2 + gap, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(a, 0f, 0f, null)
        c.drawBitmap(b, (w + gap).toFloat(), 0f, null)
        a.recycle()
        b.recycle()
        return out
    }

    private fun exportCurrentPngInternal() {
        val r = repo ?: return
        val p = currentPage()
        workScope.launch {
            val (w, h) = try { pageSize(p) } catch (e: Exception) { return@launch }
            val bmp = try {
                if (state.effectiveMode == DisplayMode.DOUBLE && p + 1 < r.count) {
                    composeTwoPages(r, p, w, h)
                } else {
                    PageComposer.compose(r, inkStore, p, w, h, state.tint,
                        state.brightness, state.visibleLayers, state.effectiveSpread,
                        state.pageRotation)
                }
            } catch (e: Exception) { null } ?: return@launch
            val ok = saveToGallery(bmp)
            bmp.recycle()
            withContext(Dispatchers.Main) { flash(if (ok) "✓" else "!") }
        }
    }

    private fun saveToGallery(bmp: Bitmap): Boolean {
        val name = "MoYe_${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MoYe")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
                ) ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: return false
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
                true
            } else {
                MediaStore.Images.Media.insertImage(contentResolver, bmp, name, "") != null
            }
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 状态栏 / 导航栏彻底不显示，且不允许边缘上滑临时滑出。 */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    /** P0：切后台前把最后一笔同步落盘，不再依赖会被 cancel 的协程。 */
    override fun onStop() {
        try {
            activeViews.values.forEach { it.flush() }
            inkStore.flush()
            saveSessionNow()
        } catch (e: Exception) { }
        super.onStop()
    }

    // ----- v4：会话、笔刷、手势、系统一致性 -----
    private fun applyAwake() {
        if (state.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applySystemDark(cfg: Configuration?) {
        if (!state.followSystemDark) return
        val c = cfg ?: resources.configuration
        val night = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val t = if (night) PageFilter.NIGHT else PageFilter.WHITE
        if (state.tint == t) return
        state.tint = t
        savePrefs()
        activeViews.values.forEach { it.setPageLook(t) }
        adapter?.invalidateRenderCache()
        adapter?.notifyDataSetChanged()
    }

    private fun saveSessionNow() {
        if (vm.currentFp.isEmpty() || !::sessionRepo.isInitialized) return
        sessionRepo.saveSession(vm.currentFp, DocSession(
            page = currentPage(),
            zoom = currentZoom,
            brushTool = currentTool,
            layer = state.activeLayer,
            tint = state.tint,
            brightness = state.brightness,
            rotation = state.pageRotation,
            showCorrection = state.showCorrection,
            displayMode = state.displayMode,
            panX = currentPanX,
            panY = currentPanY
        ))
    }

    private fun refreshUndoState() {
        if (!::root.isInitialized) return
        val u = findViewById<ImageButton>(R.id.btnUndo)
        val r = findViewById<ImageButton>(R.id.btnRedo)
        u.isEnabled = history.canUndo
        r.isEnabled = history.canRedo
        u.alpha = if (history.canUndo) 1f else 0.32f
        r.alpha = if (history.canRedo) 1f else 0.32f
    }

    private fun buildBrushSlots() {
        val row = findViewById<LinearLayout>(R.id.brushRow)
        for (i in 0 until 4) {
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginEnd = dp(5)
                }
                contentDescription = getString(R.string.cd_brush_slot)
                isClickable = true
                setOnClickListener { applyBrushSlot(i) }
                setOnLongClickListener { storeBrushSlot(i); flash("✓"); true }
            }
            brushSlots.add(v)
            row.addView(v)
        }
        refreshBrushSlots()
    }

    private fun refreshBrushSlots() {
        if (brushSlots.isEmpty() || !::sessionRepo.isInitialized) return
        brushSlots.forEachIndexed { i, v ->
            val b = sessionRepo.brush(i)
            v.background = dotDrawable(
                if (b == null) 0x22000000
                else colors[b.color.coerceIn(0, colors.lastIndex)],
                b != null && b.brushTool == currentTool && b.color == colorIndex
            )
        }
    }

    private fun applyBrushSlot(i: Int) {
        val b = sessionRepo.brush(i) ?: return
        colorIndex = b.color.coerceIn(0, colors.lastIndex)
        widthIndex = b.width.coerceIn(0, widths.lastIndex)
        eraserIndex = b.eraser.coerceIn(0, eraserWidths.lastIndex)
        currentShape = b.brushShape
        state.activeLayer = b.layer
        selectTool(b.brushTool)
        savePrefs()
        refreshTools()
    }

    private fun storeBrushSlot(i: Int) {
        sessionRepo.saveBrush(i, BrushPreset(
            brushTool = currentTool,
            color = colorIndex,
            width = widthIndex,
            eraser = eraserIndex,
            brushShape = currentShape,
            layer = state.activeLayer
        ))
        refreshBrushSlots()
    }

    /**
     * v11 悬浮橡皮唯一显隐 / 选中态出口。
     * 只看「有卷子 + 开关开着」：barsHidden 不参与，首页态强制隐藏。
     */
    private fun refreshTempEraser() {
        if (!::btnTempEraser.isInitialized || !::pager.isInitialized) return
        val inDoc = repo != null && pager.visibility == View.VISIBLE
        btnTempEraser.visibility =
            if (inDoc && state.tempEraserOn && !state.browse) View.VISIBLE else View.GONE
        btnTempEraser.isSelected = tempEraserActive
        // v12：只换图案，不再套圆形背景、不再整体染色（会染掉白色高光）
        btnTempEraser.setImageResource(
            if (tempEraserActive) R.drawable.ic_temp_eraser_on
            else R.drawable.ic_temp_eraser)
        btnTempEraser.contentDescription = getString(
            if (tempEraserActive) R.string.cd_temp_eraser_off
            else R.string.cd_temp_eraser)
    }

    private fun setupTempEraser() {
        btnTempEraser = findViewById(R.id.btnTempEraser)
        btnTempEraser.setOnClickListener { toggleTempEraser() }
        refreshTempEraser()
    }

    /** 点一下切成橡皮并高亮，再点切回上一个绘图工具。 */
    private fun toggleTempEraser() {
        if (tempEraserActive) {
            tempEraserActive = false
            selectTool(toolBeforeEraser)
        } else {
            toolBeforeEraser =
                if (currentTool == Tools.ERASER) lastDrawTool else currentTool
            tempEraserActive = true
            selectTool(Tools.ERASER)
        }
        refreshTempEraser()
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (state.volumeFlip && repo != null && ev.action == KeyEvent.ACTION_DOWN) {
            when (ev.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { step(-1); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { step(1); return true }
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun confirmClearAll() {
        val v = layoutInflater.inflate(R.layout.dialog_confirm, null)
        val d = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<ImageButton>(R.id.btnConfirmYes).setOnClickListener {
            d.dismiss()
            clearAllWithUndo()
        }
        v.findViewById<ImageButton>(R.id.btnConfirmNo).setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    private fun showImportMode(uri: Uri) {
        val v = layoutInflater.inflate(R.layout.dialog_import_mode, null)
        val d = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<ImageButton>(R.id.btnImportMerge).setOnClickListener {
            d.dismiss()
            performImport(uri, true)
        }
        v.findViewById<ImageButton>(R.id.btnImportReplace).setOnClickListener {
            d.dismiss()
            performImport(uri, false)
        }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    private fun performImport(uri: Uri, merge: Boolean) {
        val before = inkStore.snapshot()
        if (!merge) inkStore.clearAllDocuments()
        workScope.launch {
            val n = try {
                contentResolver.openInputStream(uri)?.use {
                    inkStore.importFrom(it, merge)
                } ?: 0
            } catch (e: Exception) { 0 }
            withContext(Dispatchers.Main) {
                val after = inkStore.snapshot()
                val keys = (before.keys + after.keys).toSet()
                history.push(keys.map {
                    InkHistory.Change(it, before[it] ?: emptyList<Mark>(),
                        after[it] ?: emptyList<Mark>())
                })
                refreshInksFromStore()
                refreshUndoState()
                flash(if (n > 0) "✓" else "!")
            }
        }
    }

    private fun showSnapshots() {
        val days = snapshots.days(vm.currentFp)
        if (days.isEmpty()) { flash("!"); return }
        val v = layoutInflater.inflate(R.layout.dialog_snapshots, null)
        val row = v.findViewById<LinearLayout>(R.id.snapshotRows)
        val d = AlertDialog.Builder(this).setView(v).create()
        days.forEach { stamp ->
            val t = TextView(this).apply {
                text = stamp
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icon))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                contentDescription = getString(R.string.cd_snapshot)
                setOnClickListener {
                    val before = inkStore.snapshot()
                    val snap = snapshots.restore(vm.currentFp, stamp)
                    if (snap.isNotEmpty()) {
                        history.push((before.keys + snap.keys).map { k ->
                            InkHistory.Change(k, before[k] ?: emptyList<Mark>(),
                                snap[k] ?: emptyList<Mark>())
                        })
                        inkStore.restore(snap)
                        refreshInksFromStore()
                        refreshUndoState()
                    }
                    d.dismiss()
                }
            }
            row.addView(t, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        v.findViewById<ImageButton>(R.id.btnSnapshotsClose).setOnClickListener { d.dismiss() }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout(dp(320), dp(300))
    }

    private fun showShelfActions(item: Recents.Item, onDone: () -> Unit = {}) {
        val v = layoutInflater.inflate(R.layout.dialog_shelf, null)
        val d = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<ImageButton>(R.id.btnRename).setOnClickListener {
            d.dismiss()
            showRename(item, onDone)
        }
        v.findViewById<ImageButton>(R.id.btnRemoveShelf).setOnClickListener {
            recents.remove(item.fp)
            d.dismiss()
            flash("✓")
            onDone()
        }
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
    }

    private fun showRename(item: Recents.Item, onDone: () -> Unit = {}) {
        val et = EditText(this).apply {
            setText(item.name)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.icon))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT
            requestFocus()
        }
        val d = AlertDialog.Builder(this).setView(et)
            .setPositiveButton("✓") { _, _ ->
                recents.rename(item.fp, et.text.toString())
                onDone()
            }
            .setNegativeButton("×", null)
            .create()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        d.show()
    }

    private fun jumpToNextInk() {
        val n = repo?.count ?: return
        val cur = currentPage()
        val order = ((cur + 1) until n).toList() + (0..cur).toList()
        for (p in order) {
            if (inkStore.hasInk("p$p") || inkStore.hasInk("d$p")) {
                goToPage(p)
                return
            }
        }
    }

    private fun jumpToNextBookmark() {
        val n = repo?.count ?: return
        val cur = currentPage()
        val bm = inkStore.bookmarks().filter { it in 0 until n }.sorted()
        val next = bm.firstOrNull { it > cur } ?: bm.firstOrNull()
        if (next != null && next != cur) goToPage(next)
    }

    private fun rotatePage() {
        val old = state.pageRotation
        val next = (old + 90) % 360
        val delta = next - old
        val cnt = repo?.count ?: 0
        activeViews.values.forEach { it.flush() }
        workScope.launch(Dispatchers.IO) {
            inkStore.rotatePages(delta, cnt)
            withContext(Dispatchers.Main) {
                state.pageRotation = next
                sessionRepo.setRotation(next)
                savePrefs()
                refreshInksFromStore()
                adapter?.invalidateRenderCache()
                adapter?.notifyDataSetChanged()
                saveSessionNow()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            adapter?.evictAll()
            activeViews.values.forEach { it.releaseBuffers() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemDark(newConfig)
        applyHandedness()
        // 方向变化可能改变 itemCount（横屏双页仅在横屏生效），先记住逻辑页
        val keepPage = if (repo != null) currentPage() else 0
        applyOrientationLayout()
        // 竖屏使用单页宽度适配、横屏恢复用户选的模式，并触发重渲染
        applyPageMode()
        if (repo != null) goToPage(keepPage)
        saveSessionNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("moye_page", currentPage())
    }

    override fun onDestroy() {
        repo?.close()
        workScope.cancel()
        inkStore.shutdown()
        super.onDestroy()
    }
}
