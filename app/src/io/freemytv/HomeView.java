package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen ("Matchday", the design of the HTML preview), with the macOS 27 glass:
 *  - at the top, the time, date and weather; at the top right, icon-only glass buttons (Live TV, HDMI Inputs,
 *    Settings, Sports, profile), with the focused one's name below it;
 *  - sports first: the scoreboard (the match or race on now or next, with a countdown or the minute) and beside it
 *    the "Next" list and the full schedule (Sports);
 *  - the chips (For You, Movies, Shows) and their row of posters, the selected one opened up into its cover (Recs,
 *    from TMDB); the background is the selected title's photo, heavily blurred (Backdrop);
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - always the dark look (ALWAYS_DARK): dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 270;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_FEAT_W = 800, L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 74;
    private static final float L_PLATTER_TOP = 886, L_TILE_H = 110, L_SHELF_PAD = 20;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Filmes", "Séries"};
    private static final String[] TABS_EN = {"Explore", "Movies", "Shows"};
    private static final String[] TABS_ES = {"Explorar", "Películas", "Series"};
    private static final String[] TABS_IT = {"Esplora", "Film", "Serie TV"};
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    private static final float RAIL_HEAD = 40, RAIL_ROW_H = 62, RAIL_MORE_H = 44, RAIL_GAP = 4;
    // Matchday (the design of the HTML preview): the time, date and weather in one line at the top; the scoreboard and
    // the "Next" list; the chips (For You, Movies, Shows) with the row of posters below, the selected one opened up into
    // its cover; the shelf at the bottom. Always the dark look.
    private static final float M_HEADER_BASE = 94, M_SPORT_TOP = 126, M_SPORT_H = 344, M_HERO_W = 1080, M_RAIL_X = 1196;
    private static final float M_CHIPS_TOP = 494, M_CHIPS_H = 46, M_ROW_TOP = 556, M_ROW_H = 300;
    private static final float M_POSTER_W = 200, M_COVER_W = 533, M_ROW_GAP = 24;
    private static final int Z_SPORT = 6, Z_CHIPS = 7;
    private static final boolean ALWAYS_DARK = true;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private final Trailer trailer;
    // The title card (Spotlight): 1.2 s after the trailer shows it grows to full screen; without a trailer it opens
    // with the backdrop. Its details (Recs.title) are fetched as soon as a title is focused.
    private static final long SPOT_AFTER_MS = 1200, SPOT_NO_TRAILER_MS = 1000, SPOT_LATEST_MS = 3500;
    private final Spotlight spot;
    private String spotFor;             // "kind:id" of the focused title
    private Recs.Pick spotPick;
    private Recs.Title spotTitle;
    private boolean spotWanted;         // open as soon as its details arrive
    private float spotShown = -1;       // the progress the trailer was last placed at
    private final Runnable openSpot = this::openSpot;
    private final RectF spotRect = new RectF();
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private Sports.Event chosenSport;   // picked from the "Next" list: it takes the scoreboard while it isn't over
    private final String[] raceLines = new String[2];
    private String raceLinesFor = "";
    private float raceLinesWidth;
    /** Top row: the three tabs, then the corner buttons (Live TV, HDMI, Settings, profile). */
    private static final int TOP = 9;
    private final Spring[] topFocus = new Spring[TOP];
    private final RectF[] topRects = new RectF[TOP];
    /** Explore shows 3 cards; Movies and Shows, the country's Top 10 as a carousel (up to 10). */
    private static final int CARDS = 10;
    private final Spring[] cardFocus = new Spring[CARDS];
    private final RectF[] cardRects = new RectF[CARDS];
    /** The scoreboard (0) and the "Next" list beside it: up to 4 events and the full schedule. */
    private static final int SPORT = 6;
    private List<Recs.Pick> sportCards = new ArrayList<>();
    private final Spring[] sportFocus = new Spring[SPORT];
    private final RectF[] sportRects = new RectF[SPORT];
    private int sportIndex;
    private String dateText = "", weatherText = "", weatherSymbol = "";
    /** Carousel position (fractional index of the selected card, so the widths follow it) and its caption. */
    private final Spring carPos = new Spring(0.38f, 0.82f, 0);
    private boolean carDirty;
    private String topCaption = "";
    private final Rect fitSrc = new Rect();
    private static final String[] RANKS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lDate, lWeather, lHeroTitle, lHeroSmall, lCapTitle, lCapMeta;
    private Paint lBadge;
    /** "Next": where each event is on (SkyGuide), worked out when the cards or the guide change, never while drawing. */
    private final Map<Sports.Event, List<String>> railBadges = new HashMap<>();
    private final Runnable guideChanged = () -> {
        updateRailBadges();
        heroDirty = true;
    };
    private Paint lBoard, lBoardWord, lBoardLine, lBoardRight, lBoardLineRight, lLive, lSportDay, lSportTeam, lRailHead, lRailTime, lRailSmall, lRailText, lRailMore;
    private Paint lSportMgp, lSportF1, lSportName, lSportMono, lSportLabel, lSportTitle, lSportWhen, lRank, lCaption, lAvatar, lClock, lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
        TILE_STYLE.put("nuvio", new int[] {0xFF2A1B5E, 0xFF0B0720, 0});
        TILE_STYLE.put("mono", new int[] {0xFF0F3A57, 0xFF050F18, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    // Moving an app on the shelf (OK held): the app being moved, the order before (Back puts it back), and whether the
    // hold already happened (so letting go of OK doesn't also open the app).
    private int movingTile = -1;
    private List<HomeApps.Item> shelfBeforeMove;
    private boolean okHeld;
    private static final long HOLD_MS = 600;
    private final Runnable holdOk = new Runnable() {
        @Override public void run() {
            if (!okDown || sheetOpen || zone != Z_SHELF || shelf.isEmpty() || movingTile >= 0) return;
            okHeld = true;
            movingTile = shelfIndex;
            shelfBeforeMove = new ArrayList<>(shelf);
            click.open();
            tilesDirty = true;
            spoken.focusMoved();
        }
    };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moveRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            Sports.get(getContext()).refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        trailer = new Trailer(this, recs);
        spot = new Spotlight(a);
        addView(spot.back, indexOfChild(heroLayer) + 1);     // over the picks; the trailer goes right above it
        addView(spot.front);                                 // over everything
        for (int i = 0; i < TOP; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < CARDS; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < SPORT; i++) {
            sportFocus[i] = new Spring(0.32f, 0.78f, 0);
            sportRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Sports.get(getContext()).refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(holdOk);
        trailer.release();
        if (movingTile >= 0) finishMove(false);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        if (movingTile >= 0) finishMove(false);      // Home while moving an app: the old order stays
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.hasAssistant(getContext())
                ? HomeApps.action(HomeApps.ASSISTANT, "assistant", "Gemini", "sparkles")
                : HomeApps.action(HomeApps.ASSISTANT, "assistant", Ui.tr("Busca por voz", "Voice search", "Búsqueda por voz", "Ricerca vocale"), "mic"));
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
        lightButtons.add(HomeApps.action(HomeApps.SPORTS, "sports", Ui.tr("Esportes", "Sports", "Deportes", "Sport"), "sports"));
        lightButtons.add(HomeApps.action(HomeApps.PROFILE, "profile",
                Profile.email.isEmpty() ? Ui.tr("Conta Google", "Google account") : Profile.email, "profile"));
    }

    /** The account may have appeared (permission just given): redraw the profile button with its name. */
    void refreshProfile() {
        Profile.refresh(getContext());
        Profile.loadPhoto(getContext(), () -> post(() -> { statusDirty = true; status.invalidate(); }));
        makeLightButtons();
        statusDirty = true;
        status.invalidate();
    }

    private static String[] tabs() {
        return Ui.it ? TABS_IT : Ui.es ? TABS_ES : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updateWeatherText();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(900 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(240 * kk));
        measure(tiles, width, Math.round(230 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        trailer.measure();
        measure(spot.back, width, height);
        measure(spot.front, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(840 * kk));
        place(tiles, 0, Math.round(850 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        trailer.layout();
        spot.back.layout(0, 0, width, height);
        spot.front.layout(0, 0, width, height);
    }

    // MARK: - Trailer

    /** The trailer's view goes right above the picks, under the buttons, the shelf and the sheet. */
    void addTrailerView(View v) {
        int after = indexOfChild(spot.back);
        while (after + 1 < getChildCount() && isTrailerView(getChildAt(after + 1))) after++;   // the hole, then the video
        addView(v, after + 1);
    }

    private boolean isTrailerView(View v) {
        return v instanceof android.webkit.WebView || v.getClass().getName().startsWith(Trailer.class.getName());
    }

    float trailerRadius() {
        return 18 * k * 1.05f;
    }

    /** Where the focused title's trailer plays: its opened cover, fully selected. False when there's no such card. */
    boolean trailerRect(RectF out) {
        if (!running || zone != Z_CARDS || sheetOpen || drawerOpen || cardIndex >= Math.min(cards.size(), CARDS)) return false;
        RectF base = cardRects[cardIndex];
        if (base.width() < base.height()) return false;    // still a poster, not the horizontal cover
        float s = 1.05f, cx = base.centerX(), cy = base.centerY() - 6 * k;
        out.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        return true;
    }

    /** Trailer: it plays and shows; in a moment it grows into the title card. */
    void trailerPlaying() {
        if (spot.isOpen()) {        // the card opened first: the trailer goes under it now
            spot.trailerUnder();
            trailer.round(0);
            return;
        }
        handler.removeCallbacks(openSpot);
        handler.postDelayed(openSpot, SPOT_AFTER_MS);
    }

    /** Trailer: it ended, failed or was stopped; the title card (if open) keeps its backdrop. */
    void trailerGone() {
        handler.removeCallbacks(openSpot);
        spot.trailerGone();
    }

    /** Trailer: TMDB has none for this title; the title card opens with the backdrop. */
    void trailerNone(Recs.Pick p) {
        if (p == null || spotPick != p) return;
        handler.removeCallbacks(openSpot);
        handler.postDelayed(openSpot, SPOT_NO_TRAILER_MS);
    }

    /** Called every frame with the focused title: its details are fetched once, the card closes when it changes. */
    private void wantSpot(Recs.Pick p) {
        if (p != null && p == spotPick) return;     // the same title (no new string every frame)
        if (p == null && spotFor == null) return;
        boolean film = p != null && ("movie".equals(p.kind) || "tv".equals(p.kind)) && Prefs.trailers();
        String key = film ? Recs.key(p) : null;
        if (key == null ? spotFor == null : key.equals(spotFor)) return;
        handler.removeCallbacks(openSpot);
        closeSpot();
        spotFor = key;
        spotPick = film ? p : null;
        spotTitle = null;
        spotWanted = false;
        if (!film) return;
        handler.postDelayed(openSpot, SPOT_LATEST_MS);     // after 3.5 s on the title, with or without its trailer
        recs.title(p, t -> {
            if (!key.equals(spotFor) || t == null) return;
            spotTitle = t;
            if (spotWanted) openSpot();
        });
    }

    private void openSpot() {
        if (spotPick == null || spot.isOpen() || !Recs.key(spotPick).equals(spotFor)) return;
        if (spotTitle == null) {        // details still on their way
            spotWanted = true;
            return;
        }
        spotWanted = false;
        spot.open(spotPick, spotTitle, Recs.serviceName(spotPick), trailer.playing());
        trailer.round(0);
    }

    private void closeSpot() {
        handler.removeCallbacks(openSpot);
        spotWanted = false;
        spot.close();
    }

    /** While the title card grows or shrinks: the trailer follows it, the home screen fades under it. */
    private void stepSpot() {
        float p = spot.progress();
        if (p < 0.001f && !spot.isOpen()) {
            // In its card: it follows the card as it moves (it may still be opening from poster to cover).
            if (spotShown > 0.001f) {           // just closed: corners back, the home screen fully back
                trailer.round(trailerRadius());
                motionDirty = true;
            }
            spotShown = 0;
            if (trailerRect(spotRect)) trailer.place(spotRect);
            return;
        }
        if (Math.abs(p - spotShown) < 0.001f) return;
        spotShown = p;
        motionDirty = true;
        if (trailerRect(spotRect) || spot.isOpen()) {
            if (spotRect.isEmpty()) spotRect.set(0, 0, w, h);
            spotRect.set(spotRect.left * (1 - p), spotRect.top * (1 - p), spotRect.right + (w - spotRect.right) * p,
                    spotRect.bottom + (h - spotRect.bottom) * p);
            trailer.place(spotRect);
        }
    }

    /** The title the trailer should follow right now (null: none). */
    private Recs.Pick trailerPick() {
        if (zone != Z_CARDS || sheetOpen || drawerOpen || movingTile >= 0 || cardIndex >= Math.min(cards.size(), CARDS)) return null;
        return cards.get(cardIndex);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        Profile.refresh(getContext());
        Profile.loadPhoto(getContext(), () -> post(() -> { statusDirty = true; status.invalidate(); }));
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        updateWeatherText();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 25 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 25 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 21 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lSportLabel = Ui.text(Ui.semibold, 27 * k, 0xCCFFFFFF);
        lSportLabel.setLetterSpacing(0.12f);
        lSportTitle = Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF);
        lSportWhen = Ui.text(Ui.displayMedium, 36 * k, 0xE6FFFFFF);
        lSportF1 = Ui.text(Ui.f1 != null ? Ui.f1 : Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
        lSportMgp = Ui.text(Ui.mgp != null ? Ui.mgp : Ui.displaySemibold, 54 * k, 0xFFFFFFFF);
        lSportName = Ui.text(Ui.semibold, 27 * k, 0xFFFFFFFF);
        lSportName.setTextAlign(Paint.Align.CENTER);
        // The scoreboard (matchday): the kick-off time or the minute, big; the lines above and below it.
        lBoard = Ui.numbers(Ui.displaySemibold, 66 * k, 0xFFFFFFFF);
        lBoard.setLetterSpacing(-0.035f);
        lBoard.setTextAlign(Paint.Align.CENTER);
        lBoardWord = Ui.text(Ui.displaySemibold, 44 * k, 0xFFFFFFFF);
        lBoardWord.setLetterSpacing(-0.02f);
        lBoardWord.setTextAlign(Paint.Align.CENTER);
        lBoardLine = Ui.text(Ui.medium, 24 * k, 0xD9FFFFFF);
        lBoardLine.setTextAlign(Paint.Align.CENTER);
        lBoardRight = Ui.numbers(Ui.displaySemibold, 66 * k, 0xFFFFFFFF);
        lBoardRight.setLetterSpacing(-0.035f);
        lBoardRight.setTextAlign(Paint.Align.RIGHT);
        lBoardLineRight = Ui.text(Ui.medium, 24 * k, 0xD9FFFFFF);
        lBoardLineRight.setTextAlign(Paint.Align.RIGHT);
        lLive = Ui.text(Ui.semibold, 21 * k, 0xFFFFFFFF);
        lSportDay = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        lSportDay.setTextAlign(Paint.Align.RIGHT);
        lSportTeam = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
        lSportTeam.setTextAlign(Paint.Align.CENTER);
        // The "Next" list beside it.
        lRailHead = Ui.text(Ui.semibold, 22 * k, L_DIM);
        lRailTime = Ui.numbers(Ui.displaySemibold, 30 * k, L_INK);
        lRailSmall = Ui.text(Ui.medium, 16 * k, L_FAINT);
        lRailText = Ui.text(Ui.semibold, 23 * k, L_INK);
        lRailMore = Ui.text(Ui.semibold, 21 * k, L_DIM);
        lBadge = Ui.text(Ui.semibold, 12 * k, 0xFFFFFFFF);
        lBadge.setTextAlign(Paint.Align.CENTER);
        lBadge.setLetterSpacing(0.06f);
        lSportMono = Ui.text(Ui.displaySemibold, 84 * k, 0xFFFFFFFF);
        lSportMono.setTextAlign(Paint.Align.CENTER);
        lCaption = Ui.text(Ui.medium, 30 * k, L_DIM);
        lCaption.setTextAlign(Paint.Align.RIGHT);
        lRank = Ui.text(Ui.displaySemibold, 54 * k, 0xFFFFFFFF);
        lRank.setShadowLayer(9 * k, 0, 3 * k, 0xCC000000);   // a light outer shadow: readable on white too
        lAvatar = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
        lAvatar.setTextAlign(Paint.Align.CENTER);
        lClock = Ui.numbers(Ui.displaySemibold, 56 * k, L_INK);
        lClock.setLetterSpacing(-0.02f);
        lDate = Ui.text(Ui.medium, 24 * k, L_DIM);
        lWeather = Ui.text(Ui.medium, 24 * k, L_DIM);
        lWeather.setTextAlign(Paint.Align.RIGHT);
        lHeroTitle = Ui.text(Ui.displaySemibold, 72 * k, 0xFFFFFFFF);
        lHeroTitle.setLetterSpacing(-0.02f);
        lHeroSmall = Ui.text(Ui.medium, 24 * k, 0xD9FFFFFF);
        lCapTitle = Ui.text(Ui.semibold, 22 * k, L_INK);
        lCapTitle.setTextAlign(Paint.Align.RIGHT);
        lCapMeta = Ui.text(Ui.medium, 22 * k, L_DIM);
        lCapMeta.setTextAlign(Paint.Align.RIGHT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean carMoving = carPos.step(dt);
        if (carMoving || carDirty) {
            carDirty = false;
            layoutCarousel();
        }
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | step(sportFocus, dt) | carMoving;
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        Recs.Pick focusedPick = trailerPick();
        wantSpot(focusedPick);
        trailer.want(focusedPick);
        stepSpot();
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            heroLayer.invalidate();   // the clock at the top
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = (1 - s) * (1 - Ui.clamp(spot.progress())), top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        String d = Ui.date(now);
        dateText = d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = ALWAYS_DARK || data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lClock.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xA6FFFFFF));
        lWeather.setColor(tone(L_DIM, 0xA6FFFFFF));
        lCapTitle.setColor(tone(L_INK, WHITE));
        lCapMeta.setColor(tone(L_DIM, 0xA6FFFFFF));
        lCaption.setColor(tone(L_DIM, 0xFF8E8E93));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = L_SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + L_SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < TOP; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = 6 * k, spacing = 24 * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (movingTile >= 0) top = movingTile;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
        if (movingTile >= 0) {
            c.drawText(Ui.tr("← → mover   OK confirmar   Voltar cancelar", "← → move   OK confirm   Back cancel",
                    "← → mover   OK confirmar   Atrás cancelar", "← → sposta   OK conferma   Indietro annulla"),
                    w / 2f, platterRect.bottom + 30 * k, tipPaint);
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        boolean moving = i == movingTile;
        if (movingTile >= 0) {
            // Moving an app: it grows a little more and rises; the others step aside to make room.
            if (moving) {
                s += 0.08f;
                cy -= 10 * k;
            } else {
                cx += (i < movingTile ? -16 : 16) * k;
            }
        }
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, style[0], style[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, tileFill);
            tileFill.setShader(null);
            if (fc > 0.01f) {
                tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                        Ui.alpha(0xFFFFFFFF, 0.08f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(cell, r, r, tileFill);
                tileFill.setShader(null);
            }
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        if (moving) {
            moveRing.setStyle(Paint.Style.STROKE);
            moveRing.setStrokeWidth(3 * k);
            moveRing.setColor(0xFFFFFFFF);
            c.drawRoundRect(cell, r, r, moveRing);
            float mid = cell.centerY() + Ui.halfCap(tipPaint);
            c.drawText("‹", cell.left - 20 * k, mid, tipPaint);
            c.drawText("›", cell.right + 20 * k, mid, tipPaint);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        String label = TextUtils.ellipsize(it.label, new TextPaint(drawerLabel), room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        float x = r.left + 34 * k;
        c.drawText(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city(), x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : Ui.it ? "Max " + HomeData.degrees(wx.max) + "   Min " + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        c.drawText(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ", "") + stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(HomeData.brl(data.prices[i]), right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(HomeData.change(change), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        if (cardIndex < CARDS && carPos.target != cardIndex) {
            carPos.target = cardIndex;
            carDirty = true;
        }
        if (snap) {
            carPos.snap(cardIndex);
            carDirty = true;
        }
        boolean topChanged = aim(topFocus, sheetOpen ? -1 : zone == Z_TOP ? topIndex : zone == Z_CHIPS ? tab : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        heroChanged |= aim(sportFocus, !sheetOpen && zone == Z_SPORT ? sportIndex : -1, snap);
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            RectF base = new RectF(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, base, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], base.centerX(), base.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                    Ui.alpha(0xFFFFFFFF, 0.1f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                if (movingTile < 0) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        if (zone == Z_SHELF && movingTile < 0) handler.postDelayed(holdOk, HOLD_MS);
                    }
                } else {
                    handler.removeCallbacks(holdOk);
                    if (okHeld) {
                        okHeld = false;           // the hold started moving the app: letting go opens nothing
                    } else if (movingTile >= 0) {
                        if (okDown && !e.isCanceled()) finishMove(true);
                    } else if (okDown && !e.isCanceled() && spoken.keyClick()) {
                        activate();
                    }
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * The title's trailer in the TV's YouTube app: inside the home screen it can't show on MediaTek TVs (their video
     * plane reaches only native players), and YouTube's app plays it as it should.
     */
    private void openTrailer(Recs.Pick p) {
        click.move();
        new Thread(() -> {
            String found = recs.trailer(p);
            String id = found == null || found.isEmpty() ? "" : found.split(",")[0].replaceAll("[^A-Za-z0-9_-]", "");
            handler.post(() -> {
                if (id.isEmpty()) {
                    AppLog.w("Trailer: none on TMDB for " + Recs.key(p));
                    return;
                }
                android.net.Uri uri = android.net.Uri.parse("https://www.youtube.com/watch?v=" + id);
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(new android.content.Intent(i).setPackage("com.google.android.youtube.tv"));
                } catch (RuntimeException e) {
                    try {
                        getContext().startActivity(i);
                    } catch (RuntimeException e2) {
                        AppLog.w("Trailer: no app opens YouTube links: " + e2);
                    }
                }
            });
        }, "trailer-link").start();
    }

    private void back() {
        if (spot.isOpen()) {
            closeSpot();
            click.move();
            return;
        }
        if (movingTile >= 0) {
            finishMove(false);
            return;
        }
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (spot.isOpen() && dy < 0 && dx == 0 && spotPick != null) {      // up: the trailer in the YouTube app
            openTrailer(spotPick);
            return;
        }
        if (spot.isOpen()) closeSpot();      // then the arrow does its usual job (another title stops the trailer)
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (movingTile >= 0) {
            // ← → carry the app to its new place; ↑ ↓ do nothing until OK (confirm) or Back (cancel).
            int to = Math.max(0, Math.min(shelf.size() - 1, movingTile + dx));
            if (dx == 0 || to == movingTile) return;
            List<HomeApps.Item> list = new ArrayList<>(shelf);
            list.add(to, list.remove(movingTile));
            shelf = list;
            movingTile = shelfIndex = to;
            crispLogos.clear();
            retarget(false);
            click.move();
            tilesDirty = true;
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    /** Ends moving an app: `keep` saves the new order (it stays after a restart), otherwise the old one comes back. */
    private void finishMove(boolean keep) {
        if (keep) {
            List<String> packages = new ArrayList<>();
            for (HomeApps.Item it : shelf) if (it.pkg != null) packages.add(it.pkg);
            Prefs.setShelf(packages);
        } else if (shelfBeforeMove != null) {
            HomeApps.Item moved = shelf.get(movingTile);
            shelf = shelfBeforeMove;
            shelfIndex = Math.max(0, shelf.indexOf(moved));     // focus stays on the app that was being moved
        }
        movingTile = -1;
        shelfBeforeMove = null;
        crispLogos.clear();
        retarget(false);
        click.move();
        tilesDirty = true;
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CHIPS) {
            if (cards.isEmpty()) return;
            zone = Z_CARDS;      // OK on a chip: into its row
            click.move();
            retarget(false);
            return;
        } else if (zone == Z_SPORT) {
            Recs.Pick p = sportIndex < sportCards.size() ? sportCards.get(sportIndex) : null;
            if (p == null) return;
            r = sportRects[sportIndex];
            if ("more".equals(p.kind)) {
                it = HomeApps.action(HomeApps.SPORTS, "sports", p.title, "sports");
            } else if ("row".equals(p.kind)) {
                // A "Next" event takes the scoreboard (until it is over).
                chosenSport = p.event;
                sportIndex = 0;
                click.open();
                updateCards();
                retarget(false);
                return;
            } else {
                if (p.pkg == null) return;
                it = HomeApps.app(p.pkg, Recs.serviceName(p));
            }
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(sportCards.size(), SPORT); i++) out.add(a11yId(Z_SPORT, i));
        for (int i = 0; i < 3; i++) out.add(a11yId(Z_CHIPS, i));
        for (int i = 0; i < cards.size(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                return i >= 3 ? label(lightButtons, i - 3) : "";
            case Z_CHIPS:
                return i < 3 ? tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de 3", " of 3") : "";
            case Z_SPORT: return i < sportCards.size() ? pickText(sportCards.get(i)) : "";
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF: return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i < 3 || i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = status.getTranslationY();
                break;
            case Z_CHIPS:
                if (i >= 3) return false;
                a11yRect.set(topRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SPORT:
                if (i >= Math.min(sportCards.size(), SPORT)) return false;
                a11yRect.set(sportRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_CARDS:
                if (i >= cards.size()) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CHIPS: return a11yId(Z_CHIPS, tab);
            case Z_SPORT: return sportIndex < sportCards.size() ? a11yId(Z_SPORT, sportIndex) : -1;
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case Z_TOP:
                if (i < 3 || i >= 3 + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                break;
            case Z_CHIPS:
                if (i >= 3) return;
                if (drawerOpen) closeDrawer();
                selectTab(i);
                break;
            case Z_SPORT:
                if (i >= Math.min(sportCards.size(), SPORT)) return;
                if (drawerOpen) closeDrawer();
                sportIndex = i;
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // The chips (For You, Movies, Shows) above the row of posters; the focus pill extends 26 px past the text.
        float pad = 26 * k, top = M_CHIPS_TOP * k, bottom = (M_CHIPS_TOP + M_CHIPS_H) * k;
        float x = MARGIN * k;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, top, x + tw + 2 * pad, bottom);
            x = topRects[i].right + 6 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        layoutSport();
        layoutCarousel();
        heroDirty = statusDirty = true;
    }

    /** The scoreboard at the left; at its right the "Next" list: a heading, one row per event, the full schedule. */
    private void layoutSport() {
        float top = M_SPORT_TOP * k, left = MARGIN * k;
        sportRects[0].set(left, top, left + M_HERO_W * k, top + M_SPORT_H * k);
        float x0 = M_RAIL_X * k, x1 = CONTENT_R * k, y = top + RAIL_HEAD * k;
        for (int i = 1; i < Math.min(sportCards.size(), SPORT); i++) {
            boolean more = "more".equals(sportCards.get(i).kind);
            float h = (more ? RAIL_MORE_H : RAIL_ROW_H) * k;
            if (more) y += 6 * k;
            sportRects[i].set(x0, y, x1, y + h);
            y += h + RAIL_GAP * k;
        }
    }

    /**
     * The row of posters (For You, or the country's Top 10): the selected one is opened up into its cover (M_COVER_W),
     * the others are posters (M_POSTER_W). Widths follow the fractional position, so moving left or right stretches one
     * card while the other shrinks; the cards before the selected one slide off to the left.
     */
    private void layoutCarousel() {
        int n = Math.min(cards.size(), CARDS);
        if (n == 0) return;
        float top = M_ROW_TOP * k, ch = M_ROW_H * k, g = M_ROW_GAP * k, pw = M_POSTER_W * k, fw = M_COVER_W * k;
        float pos = Math.max(0, Math.min(n - 1, carPos.value));
        int base = (int) Math.floor(pos);
        float frac = pos - base;
        float offset = base * (pw + g) + frac * (pw + (fw - pw) * (1 - frac) + g);
        // Keep the row full at its end: no empty space after the last card.
        float full = n * pw + (n - 1) * g + (fw - pw), room = (CONTENT_R - MARGIN) * k;
        offset = Math.min(offset, Math.max(0, full - room));
        float x = MARGIN * k - offset;
        for (int j = 0; j < n; j++) {
            float wj = pw + (fw - pw) * Ui.clamp(1 - Math.abs(pos - j));
            cardRects[j].set(x, top, x + wj, top + ch);
            x += wj + g;
        }
    }

    /** The scoreboard and "Next" list, and the row of the selected chip; the background follows the selected title. */
    private void updateCards() {
        List<Recs.Pick> sport = sportList();
        if (!samePicks(sport, sportCards)) {
            sportCards = sport;
            sportIndex = Math.min(sportIndex, Math.max(0, sport.size() - 1));
            layoutSport();
        }
        updateRailBadges();
        List<Recs.Pick> list = tab == 0 ? recs.forYou(Recs.FOR_YOU) : recs.top(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            carDirty = true;
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_CHIPS;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wantedAll()) recs.backdrop(p);   // all three chips preloaded: switching is instant
        topCaption = tab == 0 ? Ui.tr("Escolhidos para agora", "Picked for now", "Elegidos para ahora", "Scelti per te")
                : Ui.tr("Top 10 em ", "Top 10 in ")
                + new java.util.Locale("", Config.TMDB_REGION.trim().toUpperCase(java.util.Locale.ROOT)).getDisplayCountry(Ui.locale());
        Recs.Pick backgroundPick = null;
        for (int i = Math.max(0, Math.min(cardIndex, cards.size() - 1)); i < cards.size() && backgroundPick == null; i++) {
            if (isFilm(cards.get(i))) backgroundPick = cards.get(i);
        }
        Backdrop b = backgroundPick == null ? null : recs.backdrop(backgroundPick);
        if (cards.isEmpty() || backgroundPick == null || b != null) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /**
     * The scoreboard's list: the match or race on now or next (or the one picked from "Next"), the coming events and the
     * full schedule. Without any event, this hour's first title takes the scoreboard's place.
     */
    private List<Recs.Pick> sportList() {
        Sports sports = Sports.get(getContext());
        Recs.Pick first = sports.pick(chosenSport);
        if (first == null) chosenSport = null;
        else if (chosenSport != null && first.event != chosenSport) chosenSport = null;
        List<Recs.Pick> out = new ArrayList<>();
        if (first != null) {
            out.add(first);
            out.addAll(sports.next(first.event, 4));
        } else {
            List<Recs.Pick> films = recs.now(null);
            if (!films.isEmpty()) {
                out.add(films.get(0));
            } else {
                Recs.Pick empty = new Recs.Pick();
                empty.kind = "empty";
                empty.id = 2;
                empty.title = Ui.tr("Nenhum jogo nos próximos dias", "No games in the coming days",
                        "Ningún partido en los próximos días", "Nessun evento nei prossimi giorni");
                out.add(empty);
            }
        }
        Recs.Pick more = new Recs.Pick();
        more.kind = "more";
        more.id = 1;
        more.title = Ui.tr("Agenda completa", "Full schedule", "Agenda completa", "Calendario completo");
        out.add(more);
        return out;
    }

    private static boolean isFilm(Recs.Pick p) {
        return "movie".equals(p.kind) || "tv".equals(p.kind);
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cardIndex = 0;      // a new list starts at its first title
        carPos.snap(0);
        updateCards();
        layoutCarousel();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    /** Hero layer: the header, the scoreboard and "Next", the chips and the row of posters. */
    private void drawLightTop(Canvas c) {
        applyTone();
        drawHeader(c);
        drawRail(c);
        drawHeroCard(c);
        drawChips(c);
        drawCarousel(c);
    }

    /** The time and date at the left; the weather (symbol, temperature, city) at the right, before the buttons. */
    private void drawHeader(Canvas c) {
        float base = M_HEADER_BASE * k, x = MARGIN * k;
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), base, lClock);
        c.drawText(dateText, x + lClock.measureText(clockText) + 22 * k, base, lDate);
        if (weatherText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left) - 40 * k;
        c.drawText(weatherText, right, base - 6 * k, lWeather);
        float sw = Symbols.width(getContext(), weatherSymbol, 34 * k);
        Symbols.draw(getContext(), c, weatherSymbol, right - lWeather.measureText(weatherText) - 14 * k - sw / 2,
                base - 6 * k - Ui.halfCap(lWeather), 34 * k, tone(L_INK, 0xEBFFFFFF));
    }

    /** "18° Caleppio di Settala": worked out when the weather changes, not on every frame. */
    private void updateWeatherText() {
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            weatherText = "";
            return;
        }
        String city = Prefs.city();
        weatherText = HomeData.degrees(wx.temp) + (city.isEmpty() ? "" : "  " + city);
        weatherSymbol = Symbols.weather(wx.code, wx.isDay);
    }

    /**
     * For You, Movies, Shows: the selected one in bold on a faint pill; with focus on them, the selected one gets the
     * white pill (the tvOS focus). At the right of the row, the focused title and its details (or the row's caption).
     */
    private void drawChips(Canvas c) {
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                fill.setShader(null);
                fill.setColor(blend(tone(0x14000000, 0x1AFFFFFF), 0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        float base = (M_CHIPS_TOP + M_CHIPS_H / 2) * k + Ui.halfCap(lCapMeta), right = CONTENT_R * k;
        Recs.Pick p = zone == Z_CARDS && cardIndex < cards.size() ? cards.get(cardIndex) : null;
        if (p == null) {
            c.drawText(topCaption, right, base, lCapMeta);
            return;
        }
        String meta = p.meta == null ? "" : p.meta;
        c.drawText(meta, right, base, lCapMeta);
        float x = right - (meta.isEmpty() ? 0 : lCapMeta.measureText(meta) + 16 * k);
        String rank = tab > 0 && cardIndex < RANKS.length ? RANKS[cardIndex] : null;
        c.drawText(p.title == null ? "" : p.title, x, base, lCapTitle);
        if (rank != null) {
            x -= lCapTitle.measureText(p.title == null ? "" : p.title) + 8 * k;
            c.drawText(rank + ".", x, base, lCapTitle);
        }
    }

    /**
     * The scoreboard: a match or race (drawSportCard), or this hour's first title when there is none. Shadow, focus lift
     * and lit border as the other cards.
     */
    private void drawHeroCard(Canvas c) {
        if (sportCards.isEmpty()) return;
        RectF base = sportRects[0];
        Recs.Pick p = sportCards.get(0);
        float v = sportFocus[0].value, f = Ui.clamp(v), s = 1 + 0.02f * v;
        float bw = base.width(), bh = base.height(), r0 = 26 * k;
        float cx = base.centerX(), cy = base.centerY() - 4 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (12 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, 0.36f + 0.24f * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        if ("sport".equals(p.kind)) {
            drawSportCard(c, p, cell, r);
        } else {
            Backdrop b = isFilm(p) ? recs.backdrop(p) : null;
            Bitmap img = b == null ? null : b.wide;
            if (img != null) {
                roundImageFit(c, img, cell, r, 1);
            } else {
                fill.setShader(null);
                fill.setColor(0xFF1E2638);
                c.drawRoundRect(cell, r, r, fill);
            }
            // Darker at the bottom, where the title goes.
            fill.setShader(new LinearGradient(0, cell.top + cell.height() * 0.45f, 0, cell.bottom, 0x00000000, 0x99000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, fill);
            fill.setShader(null);
            float pad = 52 * k;
            if (isFilm(p)) {
                c.drawText(Ui.tr("Nenhum jogo nas próximas horas", "No games in the next hours",
                        "Ningún partido en las próximas horas", "Nessun evento sportivo nelle prossime ore"),
                        cell.left + pad, cell.top + 60 * k, lHeroSmall);
            }
            TextPaint tp = new TextPaint(lHeroTitle);
            c.drawText(TextUtils.ellipsize(p.title == null ? "" : p.title, tp, cell.width() - 2 * pad, TextUtils.TruncateAt.END).toString(),
                    cell.left + pad, cell.bottom - 96 * k, lHeroTitle);
            if (p.meta != null && !p.meta.isEmpty()) c.drawText(p.meta, cell.left + pad, cell.bottom - 50 * k, lHeroSmall);
        }
        if (f > 0.01f) {
            rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.95f * f), Ui.alpha(0xFFFFFFFF, 0.5f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, rim);
        }
    }


    /**
     * The scoreboard (matchday): the competition's artwork and color, its logo at the top left (the person's own in its
     * colors, one found online as a white silhouette), LIVE or the day at the top right. Two sides: their logos and
     * names, with the kick-off time (or the minute) big between them. A race: its name, session and place at the left,
     * the start time at the right.
     */
    private void drawSportCard(Canvas c, Recs.Pick p, RectF area, float r) {
        Sports sports = Sports.get(getContext());
        if (p.flat) {
            fill.setShader(null);
            fill.setColor(p.accent);
        } else if (p.gradTop != 0) {
            fill.setShader(new LinearGradient(0, area.top, 0, area.bottom, p.gradTop, p.gradBottom, Shader.TileMode.CLAMP));
        } else {
            fill.setShader(new LinearGradient(area.left, area.top, area.right, area.bottom,
                    blend(p.accent, 0xFF000000, 0.45f), p.accent, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(area, r, r, fill);
        fill.setShader(null);
        Bitmap art = sports.bitmap(p.art, null, 1280);
        if (art != null) {       // artwork behind everything, dimmed so the text reads (not on the flat card)
            roundImageFit(c, art, area, r, 1);
            if (!p.flat) {
                fill.setColor(Ui.alpha(blend(p.accent, 0xFF000000, 0.6f), 0.5f));
                c.drawRoundRect(area, r, r, fill);
            }
        }
        float pad = 44 * k, mx = area.centerX(), my = area.centerY();
        // The competition at the top center, in its own colors (the person's logo or the one found online).
        Bitmap comp = sports.bitmap(p.logoComp, null);
        float logoY = area.top + 22 * k + 30 * k;
        if (comp != null) {
            drawLogo(c, comp, mx, logoY, 280 * k, 60 * k, false);
        } else {
            lSportLabel.setTextAlign(Paint.Align.CENTER);
            c.drawText(p.label, mx, logoY + Ui.halfCap(lSportLabel), lSportLabel);
            lSportLabel.setTextAlign(Paint.Align.LEFT);
        }
        if (p.live) {
            float ph = 38 * k, pw = lLive.measureText("LIVE") + 48 * k, right = area.right - pad, top = area.top + pad + 6 * k;
            tmp.set(right - pw, top, right, top + ph);
            fill.setShader(null);
            fill.setColor(0xFFFF3B3B);
            c.drawRoundRect(tmp, ph / 2, ph / 2, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(tmp.left + 19 * k, tmp.centerY(), 5 * k, fill);
            c.drawText("LIVE", tmp.left + 32 * k, tmp.centerY() + Ui.halfCap(lLive), lLive);
        } else {
            c.drawText(p.day, area.right - pad, area.top + pad + 25 * k + Ui.halfCap(lSportDay), lSportDay);
        }
        if (!p.home.isEmpty() && !p.away.isEmpty()) {
            // Crests with the names under them, and the time between: one block, centered in the card's height.
            float size = 140 * k, cy = my + 4 * k;
            float leftX = area.left + 220 * k, rightX = area.right - 220 * k;
            drawSide(c, sports.bitmap(p.logoHome, p.home), p.home, leftX, cy, size);
            drawSide(c, sports.bitmap(p.logoAway, p.away), p.away, rightX, cy, size);
            TextPaint np = new TextPaint(lSportTeam);
            float maxName = 330 * k, nameBase = cy + size / 2 + 40 * k;
            c.drawText(TextUtils.ellipsize(p.home, np, maxName, TextUtils.TruncateAt.END).toString(), leftX, nameBase, lSportTeam);
            c.drawText(TextUtils.ellipsize(p.away, np, maxName, TextUtils.TruncateAt.END).toString(), rightX, nameBase, lSportTeam);
            boolean word = !p.board.isEmpty() && !Character.isDigit(p.board.charAt(0));
            c.drawText(p.boardTop, mx, cy - 46 * k, lBoardLine);
            c.drawText(p.board, mx, cy + (word ? 18 : 30) * k, word ? lBoardWord : lBoard);
            if (!p.boardSub.isEmpty()) c.drawText(p.boardSub, mx, cy + 70 * k, lBoardLine);
            return;
        }
        // A race (or an event without two sides): the name on up to two lines, then the session and place.
        Paint face = p.face == 1 ? lSportF1 : p.face == 2 ? lSportMgp : lSportTitle;
        float maxW = area.width() * 0.56f;
        raceLines(p.face != 0 ? p.raceName.toUpperCase(Ui.locale()) : p.raceName, face, maxW);
        // The name's lines and the session under them, centered in the card's height; the time at the right.
        int lines = (raceLines[0] != null && !raceLines[0].isEmpty() ? 1 : 0) + (raceLines[1] != null && !raceLines[1].isEmpty() ? 1 : 0);
        float lh = face.getTextSize() * 1.1f, block = lines * lh + 44 * k;
        float y = my - block / 2 + face.getTextSize() * 0.8f;
        for (String line : raceLines) {
            if (line == null || line.isEmpty()) continue;
            c.drawText(line, area.left + pad, y, face);
            y += lh;
        }
        float sx = area.left + pad, sy = y + 14 * k;
        Bitmap flag = sports.bitmap(p.flag, null);
        if (flag != null) {
            drawFlagCircle(c, flag, sx + 23 * k, sy - 9 * k, 46 * k, false);
            sx += 62 * k;
        }
        if (!p.raceLine.isEmpty()) c.drawText(p.raceLine, sx, sy, lSportWhen);
        c.drawText(p.board, area.right - pad, my + 20 * k, lBoardRight);
        if (!p.boardSub.isEmpty()) c.drawText(p.boardSub, area.right - pad, my + 56 * k, lBoardLineRight);
    }

    /** Splits a race's name into at most two lines that fit `width` (the second is shortened if needed); kept until it changes. */
    private void raceLines(String title, Paint face, float width) {
        String key = face.getTextSize() + "|" + title;
        if (key.equals(raceLinesFor) && width == raceLinesWidth) return;
        raceLinesFor = key;
        raceLinesWidth = width;
        String[] words = title.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        for (; i < words.length; i++) {
            String next = first.length() == 0 ? words[i] : first + " " + words[i];
            if (first.length() > 0 && face.measureText(next) > width) break;
            first.setLength(0);
            first.append(next);
        }
        StringBuilder rest = new StringBuilder();
        for (; i < words.length; i++) rest.append(rest.length() == 0 ? "" : " ").append(words[i]);
        TextPaint tp = new TextPaint(face);
        raceLines[0] = TextUtils.ellipsize(first.toString(), tp, width, TextUtils.TruncateAt.END).toString();
        raceLines[1] = TextUtils.ellipsize(rest.toString(), tp, width, TextUtils.TruncateAt.END).toString();
    }

    /**
     * "Next": a heading, then one row per coming event (the person's logo for the competition or its colored dot, the
     * time and day, the competition, the two sides with their badges or the race) and the full schedule. The focused
     * row turns into a white pill, as the tabs do.
     */
    private void drawRail(Canvas c) {
        Sports sports = Sports.get(getContext());
        int n = Math.min(sportCards.size(), SPORT);
        if (n < 2) return;
        lRailHead.setColor(tone(L_DIM, 0xA6FFFFFF));
        // Hidden while a corner button has focus: its name appears right there.
        if (zone != Z_TOP) c.drawText(Ui.tr("Depois", "Next", "Después", "Dopo"), M_RAIL_X * k + 20 * k, sportRects[0].top + 28 * k, lRailHead);
        for (int i = 1; i < n; i++) {
            RectF row = sportRects[i];
            Recs.Pick p = sportCards.get(i);
            float f = Ui.clamp(sportFocus[i].value), s = 1 + 0.03f * f, cx = row.centerX(), cy = row.centerY();
            cell.set(cx - row.width() * s / 2, cy - row.height() * s / 2, cx + row.width() * s / 2, cy + row.height() * s / 2);
            fill.setShader(null);
            if (f > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(cell, 18 * k * s, 18 * k * s, fill);
            } else if (i > 1 && !"more".equals(p.kind) && Ui.clamp(sportFocus[i - 1].value) < 0.01f) {
                fill.setColor(tone(0x1A000000, 0x1FFFFFFF));
                float ly = row.top - RAIL_GAP * k / 2;
                c.drawRect(row.left + 20 * k, ly - 0.75f * k, row.right - 18 * k, ly + 0.75f * k, fill);
            }
            int ink = blend(tone(L_INK, WHITE), L_INK, f), dim = blend(tone(L_DIM, 0xA6FFFFFF), L_DIM, f);
            int faint = blend(tone(L_FAINT, 0x73FFFFFF), L_FAINT, f);
            if ("more".equals(p.kind)) {
                lRailMore.setColor(blend(dim, L_INK, f));
                c.drawText(p.title, row.left + 20 * k, cy + Ui.halfCap(lRailMore), lRailMore);
                continue;
            }
            // As in the preview: the competition's logo in its own colors (else its dot) at the left; the
            // day above; below it the time, the two sides with their crests (or the race); at the right where it's on.
            Bitmap own = p.logoComp.isEmpty() ? null : sports.bitmap(p.logoComp, null);
            if (own != null) {
                drawLogo(c, own, row.left + 30 * k, cy, 36 * k, 36 * k, false);
            } else {
                fill.setColor(p.dot);
                c.drawCircle(row.left + 30 * k, cy, 5 * k, fill);
            }
            float x = row.left + 62 * k, top = row.top;
            lRailSmall.setColor(faint);
            c.drawText(p.live ? Ui.tr("Ao vivo", "Live now", "En vivo", "In diretta") : p.day, x, top + 23 * k, lRailSmall);
            float right = row.right - 18 * k;
            List<String> badges = p.event == null ? null : railBadges.get(p.event);
            if (badges != null && !badges.isEmpty()) right = drawBadges(c, badges, right, cy, f) - 12 * k;
            c.save();
            c.clipRect(x, row.top, right, row.bottom);
            float ty = top + 49 * k;
            lRailText.setColor(p.live ? 0xFFFF3B3B : ink);
            String time = p.live ? "LIVE" : p.clock;
            c.drawText(time, x, ty, lRailText);
            x += lRailText.measureText(time) + 14 * k;
            lRailText.setColor(ink);
            if (!p.home.isEmpty() && !p.away.isEmpty()) {
                x = railSide(c, sports.bitmap(p.logoHome, p.home), p.homeShort, x, ty);
                lRailText.setColor(faint);
                c.drawText("–", x, ty, lRailText);
                x += lRailText.measureText("–") + 8 * k;
                lRailText.setColor(ink);
                railSide(c, sports.bitmap(p.logoAway, p.away), p.awayShort, x, ty);
            } else {
                c.drawText(p.raceLine.isEmpty() ? p.raceName : p.raceName + ", " + p.session, x, ty, lRailText);
            }
            c.restore();
        }
    }

    private void updateRailBadges() {
        railBadges.clear();
        SkyGuide guide = SkyGuide.get();
        for (int i = 1; i < Math.min(sportCards.size(), SPORT); i++) {
            Sports.Event e = sportCards.get(i).event;
            if (e != null) railBadges.put(e, guide.badges(e, guideChanged));
        }
    }

    private final Map<String, Bitmap> broadcasters = new HashMap<>();
    private final android.graphics.PorterDuffColorFilter darkLogo =
            new android.graphics.PorterDuffColorFilter(0xFF111111, android.graphics.PorterDuff.Mode.SRC_IN);

    /** The DAZN or Sky logo (white, app/assets/broadcasters), or null for the others. */
    private Bitmap broadcaster(String name) {
        if (!name.equals("DAZN") && !name.equals("SKY")) return null;
        if (!broadcasters.containsKey(name)) {
            Bitmap b = null;
            try (java.io.InputStream in = getContext().getAssets().open("broadcasters/" + name.toLowerCase(java.util.Locale.ROOT) + ".png")) {
                b = android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException e) {
                AppLog.w("broadcaster logo " + name + ": " + e.getMessage());
            }
            broadcasters.put(name, b);
        }
        return broadcasters.get(name);
    }

    /**
     * Where it's on, right-aligned in the row: the DAZN and Sky logos (white; black on the focused row's white pill),
     * the others as small tags (Prime, TV8, Rai, L1+). Returns the left edge.
     */
    private float drawBadges(Canvas c, List<String> badges, float right, float cy, float focus) {
        float x = right;
        for (int j = badges.size() - 1; j >= 0; j--) {
            String b = badges.get(j);
            Bitmap logo = broadcaster(b);
            if (logo != null) {
                float lh = 22 * k, lw = Math.min(70 * k, logo.getWidth() * lh / logo.getHeight());
                tmp.set(x - lw, cy - lh / 2, x, cy + lh / 2);
                bitmapPaint.setColorFilter(focus > 0.5f ? darkLogo : null);
                c.drawBitmap(logo, null, tmp, bitmapPaint);
                bitmapPaint.setColorFilter(null);
                x -= lw + 12 * k;
                continue;
            }
            float bh = 20 * k, bw = lBadge.measureText(b) + 16 * k;
            tmp.set(x - bw, cy - bh / 2, x, cy + bh / 2);
            fill.setShader(null);
            fill.setColor(blend(0x29FFFFFF, 0x1F000000, focus));
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            lBadge.setColor(blend(0xFFFFFFFF, L_INK, focus));
            c.drawText(b, tmp.centerX(), cy + Ui.halfCap(lBadge), lBadge);
            x -= bw + 12 * k;
        }
        return x;
    }

    /** One side in a "Next" row: its badge (when loaded) and name; returns where the next thing goes. */
    private float railSide(Canvas c, Bitmap logo, String name, float x, float y) {
        if (logo != null) {
            drawLogo(c, logo, x + 12 * k, y - 8 * k, 24 * k, 24 * k, false);
            x += 32 * k;
        }
        c.drawText(name, x, y, lRailText);
        return x + lRailText.measureText(name) + 8 * k;
    }

    /** A flag in a circle (its middle, as a square), with a thin light ring. */
    private void drawFlagCircle(Canvas c, Bitmap flag, float cx, float cy, float d, boolean motogp) {
        float r = d / 2;
        int side = Math.min(flag.getWidth(), flag.getHeight());
        fitSrc.set((flag.getWidth() - side) / 2, (flag.getHeight() - side) / 2, (flag.getWidth() + side) / 2, (flag.getHeight() + side) / 2);
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        clip.reset();
        clip.addCircle(cx, cy, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawBitmap(flag, fitSrc, tmp, bitmapPaint);
        c.restore();
        ballStroke.setStyle(Paint.Style.STROKE);
        ballStroke.setStrokeWidth((motogp ? 1f : 2.5f) * k);
        ballStroke.setColor(motogp ? 0xFFFFFFFF : 0xB3FFFFFF);
        c.drawCircle(cx, cy, r, ballStroke);
    }

    /** One side's logo as it is; while it loads (or has none) its first letter in a faint disc. */
    private void drawSide(Canvas c, Bitmap logo, String name, float cx, float cy, float size) {
        if (logo != null) {
            drawLogo(c, logo, cx, cy, size, size, false);
            return;
        }
        fill.setShader(null);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(cx, cy, size * 0.45f, fill);
        if (!name.isEmpty()) c.drawText(name.substring(0, 1).toUpperCase(Ui.locale()), cx, cy + Ui.halfCap(lSportMono), lSportMono);
    }

    /** The logo fitted inside a box around (cx, cy); `white` turns it into a white silhouette. */
    private void drawLogo(Canvas c, Bitmap logo, float cx, float cy, float boxW, float boxH, boolean white) {
        float scale = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
        float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
        tmp.set(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2);
        bitmapPaint.setColorFilter(white ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    /** The row of posters, with the selected title opened up into its horizontal cover. */
    private void drawCarousel(Canvas c) {
        int n = Math.min(cards.size(), CARDS);
        if (n == 0) return;
        c.save();
        c.clipRect(40 * k, 0, w, h);    // posters slide under the left margin
        int top = -1;
        for (int i = 0; i < n; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < n; i++) if (i != top && cardRects[i].right > 0 && cardRects[i].left < w) drawCarouselCard(c, i);
        if (top >= 0) drawCarouselCard(c, top);
        c.restore();
    }

    /** A Top 10 card: like the others (shadow, focus lift, rim), its width and picture follow how selected it is. */
    private void drawCarouselCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = cards.get(i);
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float e = Ui.clamp(1 - Math.abs(carPos.value - i));      // 0 = poster, 1 = opened up
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = recs.backdrop(p);
        Bitmap poster = b == null ? null : b.tall, feature = b == null ? null : b.feature != null ? b.feature : b.wide;
        if (poster == null && feature == null) {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        } else {
            if (poster != null) roundImageFit(c, poster, cell, r, feature == null ? 1 : 1 - e);
            if (feature != null) roundImageFit(c, feature, cell, r, poster == null ? 1 : e);
        }
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(cell.left, cell.top, cell.left + cell.width() * 0.5f, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.22f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
            rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.9f * f), Ui.alpha(0xFFFFFFFF, 0.35f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, rim);
        }
        if (poster == null && feature == null && p.title != null) {      // no picture yet: the title on the card
            c.save();
            c.clipRect(cell);
            lCardTitle.setColor(0xFFFFFFFF);
            c.drawText(p.title, cell.left + 14 * k, cell.bottom - 18 * k, lCardTitle);
            c.restore();
            applyTone();
        }
        if (tab > 0) c.drawText(RANKS[i], cell.left + 14 * k * s, cell.top + 12 * k * s + Ui.cap(lRank), lRank);
    }

    /** The picture filling the rounded cell (center-cropped to its shape), at `alpha`. */
    private void roundImageFit(Canvas c, Bitmap image, RectF target, float r, float alpha) {
        if (alpha <= 0.01f) return;
        float ratio = target.width() / target.height();
        int sw = image.getWidth(), sh = image.getHeight();
        if ((float) sw / sh > ratio) sw = Math.round(sh * ratio); else sh = Math.round(sw / ratio);
        int sx = (image.getWidth() - sw) / 2, sy = (image.getHeight() - sh) / 2;
        int a = Math.round(255 * Math.min(1, alpha));
        if (!Ui.LEGACY) {
            fitSrc.set(sx, sy, sx + sw, sy + sh);
            clip.reset();
            clip.addRoundRect(target, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            bitmapPaint.setAlpha(a);
            c.drawBitmap(image, fitSrc, target, bitmapPaint);
            bitmapPaint.setAlpha(255);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        float scale = target.height() / sh;
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(target.left - sx * scale, target.top - sy * scale);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        imageFill.setAlpha(a);
        c.drawRoundRect(target, r, r, imageFill);
        imageFill.setAlpha(255);
        imageFill.setShader(null);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            if (lightButtons.get(i).kind == HomeApps.PROFILE) {
                drawAvatar(c, cell.centerX(), cell.centerY(), rad - 6 * k * s, f);
            } else if (lightButtons.get(i).kind == HomeApps.SPORTS) {
                drawBall(c, cell.centerX(), cell.centerY(), 15 * k * s, blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            } else if (lightButtons.get(i).kind == HomeApps.ASSISTANT && !"mic".equals(lightButtons.get(i).symbol)) {
                drawGemini(c, cell.centerX(), cell.centerY(), rad, s);
            } else if ("mic".equals(lightButtons.get(i).symbol)) {
                drawMic(c, cell.centerX(), cell.centerY(), 15 * k * s, blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            } else {
                Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                        blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            }
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private Bitmap gemini;
    private final Paint geminiRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float geminiRingFor = -1;

    /** Gemini: its star in its own colors, and a 2 px ring with the same gradient (blue below left, lavender above right). */
    private void drawGemini(Canvas c, float cx, float cy, float rad, float s) {
        if (gemini == null) {
            try (java.io.InputStream in = getContext().getAssets().open("gemini.png")) {
                gemini = android.graphics.BitmapFactory.decodeStream(in);
            } catch (java.io.IOException e) {
                AppLog.w("gemini icon: " + e.getMessage());
            }
        }
        if (gemini != null) {
            float half = 17 * k * s;
            tmp.set(cx - half, cy - half, cx + half, cy + half);
            bitmapPaint.setColorFilter(null);
            c.drawBitmap(gemini, null, tmp, bitmapPaint);
        }
        float ringR = rad - 1 * k;
        if (geminiRingFor != ringR) {     // the gradient follows the button as it grows with the focus
            geminiRingFor = ringR;
            geminiRing.setStyle(Paint.Style.STROKE);
            geminiRing.setStrokeWidth(2 * k);
            geminiRing.setShader(new LinearGradient(-ringR * 0.71f, ringR * 0.71f, ringR * 0.71f, -ringR * 0.71f,
                    new int[] {0xFF0C80FD, 0xFF0C80FD, 0xFFA392FB}, new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        }
        c.save();
        c.translate(cx, cy);
        c.drawCircle(0, 0, ringR, geminiRing);
        c.restore();
    }

    private final Path ballPath = new Path();
    private final Paint ballStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** A football as an icon: the ball's outline, a pentagon in the middle and the seams to the edge. */
    private void drawBall(Canvas c, float cx, float cy, float r, int color) {
        ballStroke.setStyle(Paint.Style.STROKE);
        ballStroke.setStrokeWidth(2.4f * k);
        ballStroke.setStrokeJoin(Paint.Join.ROUND);
        ballStroke.setStrokeCap(Paint.Cap.ROUND);
        ballStroke.setColor(color);
        c.drawCircle(cx, cy, r, ballStroke);
        ballPath.reset();
        float p = r * 0.42f;
        for (int i = 0; i < 5; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / 5;
            float px = cx + (float) Math.cos(a) * p, py = cy + (float) Math.sin(a) * p;
            if (i == 0) ballPath.moveTo(px, py); else ballPath.lineTo(px, py);
            c.drawLine(px, py, cx + (float) Math.cos(a) * r * 0.98f, cy + (float) Math.sin(a) * r * 0.98f, ballStroke);
        }
        ballPath.close();
        c.drawPath(ballPath, ballStroke);
    }

    /** A microphone as an icon (voice search): the capsule, its stand and base. */
    private void drawMic(Canvas c, float cx, float cy, float r, int color) {
        ballStroke.setStyle(Paint.Style.STROKE);
        ballStroke.setStrokeWidth(2.4f * k);
        ballStroke.setStrokeCap(Paint.Cap.ROUND);
        ballStroke.setColor(color);
        float hw = r * 0.42f;
        tmp.set(cx - hw, cy - r, cx + hw, cy + r * 0.25f);
        c.drawRoundRect(tmp, hw, hw, ballStroke);
        tmp.set(cx - r * 0.72f, cy - r * 0.55f, cx + r * 0.72f, cy + r * 0.55f);
        c.drawArc(tmp, 20, 140, false, ballStroke);
        c.drawLine(cx, cy + r * 0.55f, cx, cy + r * 0.95f, ballStroke);
        c.drawLine(cx - r * 0.38f, cy + r * 0.95f, cx + r * 0.38f, cy + r * 0.95f, ballStroke);
    }

    /** The Google account: its initial on a colored disc, or a person's silhouette while the name isn't known. */
    private void drawAvatar(Canvas c, float cx, float cy, float r, float focus) {
        fill.setShader(null);
        Paint picture = Profile.photo;
        if (picture != null) {
            Profile.drawPhoto(c, picture, cx, cy, r);
            return;
        }
        if (!Profile.initial.isEmpty()) {
            fill.setColor(Profile.color);
            c.drawCircle(cx, cy, r, fill);
            c.drawText(Profile.initial, cx, cy + Ui.halfCap(lAvatar), lAvatar);
            return;
        }
        fill.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, focus));
        c.drawCircle(cx, cy - r * 0.28f, r * 0.34f, fill);
        tmp.set(cx - r * 0.62f, cy + r * 0.16f, cx + r * 0.62f, cy + r * 1.3f);
        c.save();
        c.clipRect(cx - r, cy - r, cx + r, cy + r * 0.78f);
        c.drawRoundRect(tmp, r * 0.62f, r * 0.62f, fill);
        c.restore();
    }

    /** Carousel: the background follows the selected title. */
    private void selectedBackdrop() {
        if (cardIndex >= cards.size()) return;
        Backdrop b = recs.backdrop(cards.get(cardIndex));
        if (b != null) showBackdrop(b);
    }

    /**
     * Matchday focus, top to bottom: the corner buttons; the scoreboard and its "Next" list (← → between them, ↑ ↓ along
     * the list); the chips; the row of posters; the shelf (and ↓ there opens the drawer, handled by move()).
     */
    private boolean moveLight(int dx, int dy) {
        int buttons = lightButtons.size();
        int sportLast = Math.min(sportCards.size(), SPORT) - 1;
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                zone = cards.isEmpty() ? Z_CHIPS : Z_CARDS;
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                    selectedBackdrop();
                } else if (dy < 0) {
                    zone = Z_CHIPS;
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_CHIPS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, tab + dx));
                    if (next == tab) return true;
                    selectTab(next);
                } else if (dy < 0) {
                    if (sportCards.isEmpty()) return true;
                    zone = Z_SPORT;
                    sportIndex = 0;
                } else {
                    zone = cards.isEmpty() ? Z_SHELF : Z_CARDS;
                }
                break;
            case Z_SPORT:
                if (sportIndex == 0) {
                    if (dx > 0 && sportLast >= 1) sportIndex = 1;
                    else if (dx != 0) return true;
                    else if (dy < 0) toButtons(sportRects[0].centerX(), buttons);
                    else zone = Z_CHIPS;
                } else if (dx < 0) {
                    sportIndex = 0;
                } else if (dx > 0) {
                    return true;
                } else if (dy < 0) {
                    if (sportIndex > 1) sportIndex--;
                    else toButtons(sportRects[sportIndex].centerX(), buttons);
                } else if (sportIndex < sportLast) {
                    sportIndex++;
                } else {
                    zone = Z_CHIPS;
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(3, Math.min(3 + buttons - 1, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                } else if (dy > 0) {
                    if (sportCards.isEmpty()) {
                        zone = Z_CHIPS;
                    } else {
                        zone = Z_SPORT;
                        sportIndex = sportLast >= 1 && topRects[topIndex].centerX() > M_RAIL_X * k ? 1 : 0;
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /** Up from the scoreboard: the corner button nearest to `x`. */
    private void toButtons(float x, int buttons) {
        if (buttons == 0) return;
        zone = Z_TOP;
        int best = 3;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 3; i < 3 + buttons; i++) {
            float d = Math.abs(topRects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        topIndex = best;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
