import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Point
import java.awt.Color
import java.awt.Cursor
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.border.EmptyBorder
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicButtonUI

@Composable
internal fun UnifiedVpnTray(
    tooltip: String,
    openText: String,
    toggleText: String,
    toggleEnabled: Boolean,
    routingText: String?,
    routingEnabled: Boolean,
    settingsText: String,
    quitText: String,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRoutingToggle: () -> Unit,
    onSettings: () -> Unit,
    onQuit: () -> Unit
) {
    val controller = androidx.compose.runtime.remember {
        UnifiedVpnTrayController(
            TrayMenuState(
                tooltip = tooltip,
                openText = openText,
                toggleText = toggleText,
                toggleEnabled = toggleEnabled,
                routingText = routingText,
                routingEnabled = routingEnabled,
                settingsText = settingsText,
                quitText = quitText,
                onOpen = onOpen,
                onToggle = onToggle,
                onRoutingToggle = onRoutingToggle,
                onSettings = onSettings,
                onQuit = onQuit
            )
        )
    }

    SideEffect {
        controller.update(
            TrayMenuState(
                tooltip = tooltip,
                openText = openText,
                toggleText = toggleText,
                toggleEnabled = toggleEnabled,
                routingText = routingText,
                routingEnabled = routingEnabled,
                settingsText = settingsText,
                quitText = quitText,
                onOpen = onOpen,
                onToggle = onToggle,
                onRoutingToggle = onRoutingToggle,
                onSettings = onSettings,
                onQuit = onQuit
            )
        )
    }

    DisposableEffect(controller) {
        controller.attach()
        onDispose(controller::close)
    }
}

private data class TrayMenuState(
    val tooltip: String,
    val openText: String,
    val toggleText: String,
    val toggleEnabled: Boolean,
    val routingText: String?,
    val routingEnabled: Boolean,
    val settingsText: String,
    val quitText: String,
    val onOpen: () -> Unit,
    val onToggle: () -> Unit,
    val onRoutingToggle: () -> Unit,
    val onSettings: () -> Unit,
    val onQuit: () -> Unit
)

private class UnifiedVpnTrayController(initialState: TrayMenuState) {
    private val state = AtomicReference(initialState)
    private val closed = AtomicBoolean(false)
    private var trayIcon: TrayIcon? = null
    private var popupHost: JFrame? = null
    private var focusTimer: Timer? = null

    fun update(newState: TrayMenuState) {
        state.set(newState)
        trayIcon?.toolTip = newState.tooltip
    }

    fun attach() {
        runOnEventThread {
            if (closed.get() || trayIcon != null || !SystemTray.isSupported()) return@runOnEventThread
            runCatching {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            }

            val iconResource = requireNotNull(
                UnifiedVpnTrayController::class.java.getResource("/LinuxIcon.png")
            ) { "LinuxIcon.png is missing from desktop resources" }
            val icon = TrayIcon(ImageIO.read(iconResource), state.get().tooltip).apply {
                isImageAutoSize = true
            }
            val host = JFrame().apply {
                isUndecorated = true
                type = Window.Type.UTILITY
                isAlwaysOnTop = true
                focusableWindowState = true
                isAutoRequestFocus = true
                isResizable = false
                size = Dimension(1, 1)
                addWindowFocusListener(object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent) {
                        hideMenu()
                    }
                })
            }

            icon.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    when {
                        event.isPopupTrigger || SwingUtilities.isRightMouseButton(event) ->
                            showMenu(host, event.x, event.y)
                        SwingUtilities.isLeftMouseButton(event) -> state.get().onOpen()
                    }
                }
            })

            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            popupHost = host
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runOnEventThread {
            trayIcon?.let { SystemTray.getSystemTray().remove(it) }
            trayIcon = null
            hideMenu()
            popupHost?.dispose()
            popupHost = null
        }
    }

    private fun showMenu(host: JFrame, x: Int, y: Int) {
        hideMenu()
        val current = state.get()
        val panel = JPanel(GridLayout(0, 1)).apply {
            isOpaque = true
            background = TRAY_MENU_BACKGROUND
            border = EmptyBorder(2, 2, 2, 2)
        }
        val items = buildList {
            add(createMenuItem(current.openText, enabled = true, current.onOpen))
            add(createMenuItem(current.toggleText, current.toggleEnabled, current.onToggle))
            current.routingText?.let { text ->
                add(createMenuItem(text, current.routingEnabled, current.onRoutingToggle))
            }
            add(createMenuItem(current.settingsText, enabled = true, current.onSettings))
            add(createMenuItem(current.quitText, enabled = true, current.onQuit))
        }
        items.forEach(panel::add)

        val menuWidth = maxOf(
            TRAY_MENU_MIN_WIDTH,
            items.maxOf { it.preferredSize.width } + TRAY_MENU_BORDER_SIZE * 2
        )
        items.forEach { item ->
            item.preferredSize = Dimension(
                menuWidth - TRAY_MENU_BORDER_SIZE * 2,
                TRAY_MENU_ITEM_HEIGHT
            )
        }
        host.contentPane.removeAll()
        host.contentPane.add(panel)
        host.pack()

        val bounds = screenBoundsFor(Point(x, y))
        val menuSize = host.size
        val left = (x - menuSize.width).coerceIn(bounds.x, bounds.x + bounds.width - menuSize.width)
        val top = (y - menuSize.height).coerceIn(bounds.y, bounds.y + bounds.height - menuSize.height)
        host.setLocation(left, top)
        host.isVisible = true
        host.toFront()
        focusTimer = Timer(TRAY_MENU_FOCUS_DELAY_MS) {
            if (host.isVisible) {
                host.toFront()
                host.requestFocus()
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun hideMenu() {
        focusTimer?.stop()
        focusTimer = null
        popupHost?.isVisible = false
    }

    private fun createMenuItem(
        text: String,
        enabled: Boolean,
        action: () -> Unit
    ): JButton = JButton(text).apply {
        setUI(object : BasicButtonUI() {
            override fun paintText(
                graphics: Graphics,
                component: JComponent,
                textRect: Rectangle,
                value: String
            ) {
                val button = component as AbstractButton
                graphics.font = button.font
                graphics.color = if (button.isEnabled) {
                    button.foreground
                } else {
                    TRAY_MENU_DISABLED_FOREGROUND
                }
                graphics.drawString(
                    value,
                    textRect.x,
                    textRect.y + graphics.fontMetrics.ascent
                )
            }
        })
        font = Font("Segoe UI", Font.PLAIN, 14)
        isEnabled = enabled
        horizontalAlignment = SwingConstants.LEFT
        cursor = if (enabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        isOpaque = true
        isBorderPainted = false
        isContentAreaFilled = true
        isFocusPainted = false
        background = TRAY_MENU_BACKGROUND
        foreground = if (enabled) TRAY_MENU_FOREGROUND else TRAY_MENU_DISABLED_FOREGROUND
        border = EmptyBorder(5, 12, 5, 16)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) {
                if (isEnabled) {
                    background = TRAY_MENU_SELECTION
                    foreground = TRAY_MENU_SELECTION_FOREGROUND
                }
            }

            override fun mouseExited(event: MouseEvent) {
                background = TRAY_MENU_BACKGROUND
                foreground = if (isEnabled) TRAY_MENU_FOREGROUND else TRAY_MENU_DISABLED_FOREGROUND
            }
        })
        addActionListener {
            hideMenu()
            action()
        }
    }

    private fun screenBoundsFor(point: Point) =
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .flatMap { it.configurations.asIterable() }
            .firstOrNull { it.bounds.contains(point) }
            ?.bounds
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds

    private fun runOnEventThread(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            action()
        } else {
            EventQueue.invokeAndWait(action)
        }
    }
}

private const val TRAY_MENU_MIN_WIDTH = 218
private const val TRAY_MENU_ITEM_HEIGHT = 36
private const val TRAY_MENU_BORDER_SIZE = 2
private const val TRAY_MENU_FOCUS_DELAY_MS = 250
private val TRAY_MENU_BACKGROUND = Color(27, 35, 44)
private val TRAY_MENU_FOREGROUND = Color(239, 243, 247)
private val TRAY_MENU_DISABLED_FOREGROUND = Color(126, 137, 148)
private val TRAY_MENU_SELECTION = Color(39, 52, 66)
private val TRAY_MENU_SELECTION_FOREGROUND = Color(255, 255, 255)
