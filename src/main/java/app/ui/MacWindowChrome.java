package app.ui;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * macOS 제목 막대(닫기·최소화·전체화면 버튼 줄)를 앱 테마에 맞춘다.
 *
 * <p>JavaFX 21 은 제목 막대 색을 바꾸는 API 가 없어, 앱을 다크 모드로 바꿔도 제목 막대는
 * 시스템 모양(라이트면 흰색) 그대로 남아 툴바와 따로 놀았다. 여기서는 AppKit 을 직접 불러
 * <ul>
 *   <li>앱 모양(NSAppearance)을 앱 테마와 같게 — 제목 글자·신호등 버튼·파일 열기 창도 함께 바뀐다</li>
 *   <li>제목 막대를 투명하게 하고 창 배경을 {@link Styles#bgApp()} 으로 — 툴바와 한 면이 된다</li>
 * </ul>
 * 실패해도 앱 동작에는 영향이 없도록, 오류가 나면 한 번 기록하고 이후로는 아무것도 하지 않는다.
 * macOS 가 아니면 아무것도 하지 않는다.</p>
 *
 * <p>반드시 JavaFX Application Thread(macOS 에서는 AppKit 메인 스레드)에서 불러야 한다.</p>
 */
public final class MacWindowChrome {
    private MacWindowChrome() {}

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static Function msgSend, getClass, registerSel;
    private static boolean failed = false;

    /** 창이 화면에 뜬 뒤(show 이후)와 테마가 바뀔 때마다 부른다. */
    public static void apply(Stage stage, boolean dark, Color background) {
        if (!IS_MAC || failed || stage == null || !stage.isShowing()) return;
        try {
            init();
            Pointer app = send(cls("NSApplication"), "sharedApplication");
            Pointer appearance = send(cls("NSAppearance"), "appearanceNamed:",
                    nsString(dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua"));
            if (app == null || appearance == null) return;
            sendVoid(app, "setAppearance:", appearance);

            Pointer window = findWindow(app, stage.getTitle());
            if (window == null) return;
            Pointer color = send(cls("NSColor"), "colorWithSRGBRed:green:blue:alpha:",
                    background.getRed(), background.getGreen(), background.getBlue(), 1.0);
            sendVoid(window, "setTitlebarAppearsTransparent:", 1);
            sendVoid(window, "setBackgroundColor:", color);
        } catch (Throwable t) {
            failed = true;
            System.err.println("[MacWindowChrome] 제목 막대 꾸미기를 건너뜁니다: " + t);
        }
    }

    private static void init() {
        if (msgSend != null) return;
        NativeLibrary objc = NativeLibrary.getInstance("objc");
        msgSend = objc.getFunction("objc_msgSend");
        getClass = objc.getFunction("objc_getClass");
        registerSel = objc.getFunction("sel_registerName");
    }

    /** NSApp.windows 중 제목이 같은 창 (JavaFX Stage 와 NSWindow 를 잇는 공개 방법이 없어 제목으로 찾는다). */
    private static Pointer findWindow(Pointer app, String title) {
        if (title == null) return null;
        Pointer windows = send(app, "windows");
        if (windows == null) return null;
        long n = msgSend.invokeLong(new Object[]{windows, sel("count")});
        for (long i = 0; i < n; i++) {
            Pointer w = send(windows, "objectAtIndex:", i);
            if (w == null) continue;
            Pointer t = send(w, "title");
            Pointer utf8 = (t == null) ? null : send(t, "UTF8String");
            if (utf8 != null && title.equals(utf8.getString(0, "UTF-8"))) return w;
        }
        return null;
    }

    private static Pointer cls(String name) {
        return getClass.invokePointer(new Object[]{name});
    }

    private static Pointer sel(String name) {
        return registerSel.invokePointer(new Object[]{name});
    }

    private static Pointer nsString(String s) {
        return send(cls("NSString"), "stringWithUTF8String:", s);
    }

    private static Pointer send(Pointer receiver, String selector, Object... args) {
        return msgSend.invokePointer(withReceiver(receiver, selector, args));
    }

    private static void sendVoid(Pointer receiver, String selector, Object... args) {
        msgSend.invokeVoid(withReceiver(receiver, selector, args));
    }

    private static Object[] withReceiver(Pointer receiver, String selector, Object[] args) {
        Object[] all = new Object[args.length + 2];
        all[0] = receiver;
        all[1] = sel(selector);
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
    }
}
