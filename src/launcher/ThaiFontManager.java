package launcher;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;

/**
 * Thai font support for the whole Swing UI. Without this, Thai characters
 * appear as boxes/question marks in MacMahon's tables, menus and labels.
 * Extracted from MacMahonLauncher — behavior unchanged.
 */
final class ThaiFontManager {

    private ThaiFontManager() {}

    private static String chosenFontName = "TH Sarabun New";
    private static int tableFoundCount = 0;

    /** The Thai-compatible font family resolved for this system (see setupThaiFont()). */
    static String getChosenFontName() {
        return chosenFontName;
    }

    /**
     * Set up Thai-compatible font for all Swing components.
     * Without this, Thai characters appear as boxes/question marks.
     */
    static void setupThaiFont() {
        try {
            // Find best Thai-compatible font available on this system.
            // "Thonburi"/"Ayuthaya"/"Krungthep"/"Silom"/"Sathu" are Apple's own
            // Thai-script fonts, bundled on macOS (verified present via
            // GraphicsEnvironment on a real Mac) — harmless on Windows since
            // they simply won't be in the available-fonts set there.
            String[] preferredFonts = {"TH Sarabun New", "TH SarabunPSK", "Sarabun",
                                        "Thonburi", "Ayuthaya", "Krungthep", "Silom", "Sathu",
                                        "Tahoma", "Leelawadee UI", "Segoe UI", "Cordia New",
                                        "Microsoft Sans Serif"};
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            java.util.Set<String> available = new java.util.HashSet<String>(
                java.util.Arrays.asList(ge.getAvailableFontFamilyNames())
            );

            String chosenFont = "TH Sarabun New"; // default fallback
            for (String pf : preferredFonts) {
                if (available.contains(pf)) {
                    chosenFont = pf;
                    break;
                }
            }
            chosenFontName = chosenFont; // store globally

            System.out.println("[Launcher] Using font: " + chosenFont);
            Font thaiFont = new Font(chosenFont, Font.PLAIN, 12);

            // Diagnostic: can this font display Thai?
            int canDisplay = thaiFont.canDisplayUpTo("กขคงจฉช");
            System.out.println("[Launcher] Font '" + chosenFont + "' canDisplayUpTo Thai: " + canDisplay
                + " (family=" + thaiFont.getFamily() + ", name=" + thaiFont.getFontName() + ")");
            if (canDisplay != -1) {
                System.err.println("[Launcher] WARNING: Font cannot display Thai characters!");
            }

            // Override ALL Swing component default fonts
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // ignore
            }

            javax.swing.plaf.FontUIResource fontResource =
                new javax.swing.plaf.FontUIResource(chosenFont, Font.PLAIN, 12);

            java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
            while (keys.hasMoreElements()) {
                Object key = keys.nextElement();
                Object value = UIManager.get(key);
                if (value instanceof javax.swing.plaf.FontUIResource) {
                    UIManager.put(key, fontResource);
                }
            }

            // Also set specific components that might be missed
            String[] fontKeys = {
                "Label.font", "Button.font", "MenuItem.font", "Menu.font",
                "MenuBar.font", "ComboBox.font", "Table.font", "TableHeader.font",
                "TextField.font", "TextArea.font", "OptionPane.messageFont",
                "OptionPane.buttonFont", "TabbedPane.font", "List.font",
                "Panel.font", "ScrollPane.font", "ToolTip.font",
                "Tree.font", "Spinner.font", "TitledBorder.font",
                "EditorPane.font", "FormattedTextField.font"
            };
            for (String fk : fontKeys) {
                UIManager.put(fk, fontResource);
            }

            // Install global AWT listener to catch ALL components as they're added
            installGlobalFontListener(chosenFont);

        } catch (Exception e) {
            System.err.println("[Launcher] Font setup warning: " + e.getMessage());
        }
    }

    /**
     * Global AWT event listener that intercepts ALL component additions.
     * When any component is added to any container, we set Thai-compatible font on it.
     * This catches MacMahon's tables/labels as they're created — no timer needed.
     */
    private static void installGlobalFontListener(final String fontName) {
        final Font normal = new Font(fontName, Font.PLAIN, 12);
        final Font bold = new Font(fontName, Font.BOLD, 12);

        Toolkit.getDefaultToolkit().addAWTEventListener(new java.awt.event.AWTEventListener() {
            public void eventDispatched(AWTEvent event) {
                if (event instanceof java.awt.event.ContainerEvent) {
                    java.awt.event.ContainerEvent ce = (java.awt.event.ContainerEvent) event;
                    if (ce.getID() == java.awt.event.ContainerEvent.COMPONENT_ADDED) {
                        final Component child = ce.getChild();
                        setThaiFontOnComponent(child, normal, bold);
                        // For JTables, also schedule renderer wrapping after setup completes
                        if (child instanceof JTable) {
                            SwingUtilities.invokeLater(new Runnable() {
                                public void run() {
                                    wrapTableRenderers((JTable) child, normal, bold);
                                }
                            });
                        }
                    }
                }
            }
        }, AWTEvent.CONTAINER_EVENT_MASK);
        System.out.println("[Launcher] Global font listener installed");
    }

    /**
     * Set Thai font on a single component.
     */
    private static void setThaiFontOnComponent(Component comp, Font normal, Font bold) {
        // Preserve original style (bold/italic) — only change font family
        Font current = comp.getFont();
        if (current == null) {
            comp.setFont(normal);
        } else if (!chosenFontName.equals(current.getName())) {
            comp.setFont(new Font(chosenFontName, current.getStyle(), current.getSize()));
        }
        if (comp instanceof JTable) {
            JTable table = (JTable) comp;
            if (table.getTableHeader() != null) {
                Font hf = table.getTableHeader().getFont();
                if (hf != null && !chosenFontName.equals(hf.getName())) {
                    table.getTableHeader().setFont(new Font(chosenFontName, hf.getStyle(), hf.getSize()));
                }
            }
        }
    }

    /**
     * Recursively apply Thai-compatible font to ALL components
     * in the MacMahon JFrame (overrides MacMahon's own font choices).
     */
    static void applyThaiFontToAll(Container container) {
        Font thaiFont = new Font(chosenFontName, Font.PLAIN, 12);
        Font thaiFontBold = new Font(chosenFontName, Font.BOLD, 12);
        tableFoundCount = 0;
        int changed = applyFontRecursive(container, thaiFont, thaiFontBold);
        // Only log when this pass actually changed something — the maintenance
        // timer calls this every 5s and most passes are no-ops once fonts settle.
        if (changed > 0) {
            System.out.println("[Launcher] Thai font applied — " + changed + " components changed, "
                + tableFoundCount + " JTables found");
        }
    }

    private static int applyFontRecursive(Container container, Font normal, Font bold) {
        int changed = 0;
        for (Component comp : container.getComponents()) {
            // Preserve original style (bold/italic) — only change font family
            if (preserveFontFamily(comp)) changed++;

            if (comp instanceof JTable) {
                JTable table = (JTable) comp;
                wrapTableRenderers(table, normal, bold);
                tableFoundCount++;
            }
            if (comp instanceof JMenuBar) {
                JMenuBar mb = (JMenuBar) comp;
                for (int i = 0; i < mb.getMenuCount(); i++) {
                    JMenu menu = mb.getMenu(i);
                    if (menu != null) {
                        if (preserveFontFamily(menu)) changed++;
                        for (int j = 0; j < menu.getItemCount(); j++) {
                            JMenuItem item = menu.getItem(j);
                            if (item != null && preserveFontFamily(item)) changed++;
                        }
                    }
                }
            }
            if (comp instanceof Container) {
                changed += applyFontRecursive((Container) comp, normal, bold);
            }
        }
        return changed;
    }

    /**
     * Change font family to Thai-compatible font while preserving style (bold/italic) and size.
     * No-op if the component's font family already matches — avoids churning
     * setFont()/repaint() on every component on every maintenance timer tick.
     * Returns true iff the font was actually changed.
     */
    private static boolean preserveFontFamily(Component comp) {
        Font current = comp.getFont();
        if (current != null && !chosenFontName.equals(current.getName())) {
            comp.setFont(new Font(chosenFontName, current.getStyle(), current.getSize()));
            return true;
        }
        return false;
    }

    /**
     * Wrap ALL renderers on a JTable with ThaiCellRenderer to force Thai font.
     */
    private static void wrapTableRenderers(JTable table, Font normal, Font bold) {
        try {
            boolean headerWrapped = false;
            // Wrap header renderer
            if (table.getTableHeader() != null) {
                TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
                if (headerRenderer != null && !(headerRenderer instanceof ThaiCellRenderer)) {
                    table.getTableHeader().setDefaultRenderer(new ThaiCellRenderer(headerRenderer, bold));
                    headerWrapped = true;
                }
            }

            // Wrap default renderers for ALL column classes used in this table
            java.util.Set<Class<?>> classesWrapped = new java.util.HashSet<Class<?>>();
            classesWrapped.add(Object.class);
            classesWrapped.add(String.class);
            classesWrapped.add(Number.class);
            classesWrapped.add(Integer.class);
            classesWrapped.add(Double.class);
            classesWrapped.add(Float.class);
            classesWrapped.add(Boolean.class);
            // Also add actual column classes from the table model
            for (int col = 0; col < table.getColumnCount(); col++) {
                try {
                    classesWrapped.add(table.getColumnClass(col));
                } catch (Exception ex) { /* ignore */ }
            }
            for (Class<?> cls : classesWrapped) {
                try {
                    TableCellRenderer defRenderer = table.getDefaultRenderer(cls);
                    if (defRenderer != null && !(defRenderer instanceof ThaiCellRenderer)) {
                        table.setDefaultRenderer(cls, new ThaiCellRenderer(defRenderer, normal));
                    }
                } catch (Exception ex) { /* ignore */ }
            }

            // Wrap per-column renderers
            int tableCount = 0;
            if (table.getColumnModel() != null) {
                for (int col = 0; col < table.getColumnModel().getColumnCount(); col++) {
                    TableColumn tc = table.getColumnModel().getColumn(col);
                    TableCellRenderer colRenderer = tc.getCellRenderer();
                    if (colRenderer != null && !(colRenderer instanceof ThaiCellRenderer)) {
                        tc.setCellRenderer(new ThaiCellRenderer(colRenderer, normal));
                        tableCount++;
                    }
                    // If no per-column renderer, force one from the effective renderer
                    if (colRenderer == null) {
                        try {
                            Class<?> colClass = table.getColumnClass(col);
                            TableCellRenderer effective = table.getDefaultRenderer(colClass);
                            if (effective != null && !(effective instanceof ThaiCellRenderer)) {
                                tc.setCellRenderer(new ThaiCellRenderer(effective, normal));
                                tableCount++;
                            }
                        } catch (Exception ex) { /* ignore */ }
                    }
                }
            }
            // Only log when something was actually (re)wrapped — this runs on every
            // maintenance timer tick and is normally a no-op after the first pass.
            if (tableCount > 0 || headerWrapped) {
                System.out.println("[Launcher] Wrapped " + tableCount + " column renderers on "
                    + table.getClass().getSimpleName() + " (" + table.getColumnCount() + " cols)");
            }
        } catch (Exception e) {
            System.err.println("[Launcher] wrapTableRenderers error: " + e.getMessage());
        }
    }

    /**
     * Background timer that periodically re-applies Thai font to all visible JFrames.
     * This catches tables/components that MacMahon creates AFTER our initial font pass
     * (e.g., when a tournament is opened or rounds are created).
     */
    static void startFontMaintenanceTimer() {
        Timer fontTimer = new Timer(5000, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                Frame[] frames = Frame.getFrames();
                for (Frame frame : frames) {
                    if (frame instanceof JFrame && frame.isVisible()) {
                        applyThaiFontToAll((Container) frame);
                    }
                }
            }
        });
        fontTimer.setRepeats(true);
        fontTimer.start();
        System.out.println("[Launcher] Font maintenance timer started (every 5s)");
    }

    /**
     * Wrapper renderer that forces Thai-compatible font on any JTable cell renderer.
     * This ensures MacMahon's custom renderers (WalllistCellRenderer, PairingsCellRenderer)
     * use Thai-compatible font instead of the default Dialog font.
     */
    private static class ThaiCellRenderer implements TableCellRenderer {
        private final TableCellRenderer delegate;
        private final Font font;

        ThaiCellRenderer(TableCellRenderer delegate, Font font) {
            this.delegate = delegate;
            this.font = font;
        }

        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            // Recursively set Thai font on ALL sub-components
            // (handles compound renderers that use JPanel with JLabels inside)
            setFontDeep(c, font);
            return c;
        }

        private void setFontDeep(Component comp, Font f) {
            // Preserve bold/italic style from original font.
            // Skip setFont() when the family already matches — this runs on
            // every cell repaint, so a redundant setFont() here means every
            // visible cell repaints itself on every paint pass.
            // Compare via getName() (always the exact string passed to the Font
            // constructor), not getFamily() (which the OS may resolve to
            // "Dialog" if the family isn't installed, masking real changes).
            Font current = comp.getFont();
            if (current == null) {
                comp.setFont(f);
            } else if (!f.getName().equals(current.getName())) {
                comp.setFont(new Font(f.getName(), current.getStyle(), current.getSize()));
            }
            if (comp instanceof Container) {
                for (Component child : ((Container) comp).getComponents()) {
                    setFontDeep(child, f);
                }
            }
        }
    }
}