package launcher;

import javax.swing.*;
import java.io.*;

/**
 * Java runtime detection: when the launcher is started on an older Java,
 * find Java MIN_JAVA_MAJOR (25) or newer on this machine and relaunch.
 * This allows double-clicking the JAR with any installed Java version.
 * Extracted from MacMahonLauncher — behavior unchanged.
 */
final class JavaFinder {

    private JavaFinder() {}

    /** Minimum Java major version MacMahon 3.10 needs to run. */
    static final int MIN_JAVA_MAJOR = 25;

    /**
     * If not running on Java 25+, search for it and relaunch.
     */
    static void ensureJava25() {
        String version = System.getProperty("java.version");
        int major = getMajorVersion(version);
        System.out.println("[Launcher] Java version: " + version + " (major=" + major + ")");

        if (major >= MIN_JAVA_MAJOR) return; // OK

        System.out.println("[Launcher] Need Java 25+, searching...");
        String java25 = findJava25Path();
        if (java25 != null) {
            System.out.println("[Launcher] Found Java 25+: " + java25);
            if (relaunchWithJava(java25)) {
                System.exit(0);
            } else {
                MacMahonLauncher.showError("เปิดโปรแกรมใหม่ด้วย Java 25 ไม่สำเร็จ\n"
                    + "Java path: " + java25 + "\n\n"
                    + "ลองรัน macmahon-tesuji.jar ด้วยตนเองผ่าน command line:\n"
                    + "\"" + java25 + "\" -jar macmahon-tesuji.jar");
                System.exit(1);
            }
        } else {
            JOptionPane.showMessageDialog(null,
                "MacMahon requires Java 25+\n\n" +
                "Current: Java " + version + "\n\n" +
                javaInstallHint(),
                "MacMahon Launcher", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String javaInstallHint() {
        if (isMac()) {
            return "ติดตั้ง Java 25 (Temurin) ผ่าน Homebrew:\n"
                + "  brew install --cask temurin\n\n"
                + "หรือดาวน์โหลด .pkg จาก:\n"
                + "https://adoptium.net/temurin/releases/?version=25";
        }
        return "Download Amazon Corretto 25:\n"
            + "https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.msi";
    }

    static int getMajorVersion(String version) {
        if (version.startsWith("1.")) version = version.substring(2);
        int dot = version.indexOf('.');
        if (dot > 0) version = version.substring(0, dot);
        try { return Integer.parseInt(version); }
        catch (NumberFormatException e) { return 0; }
    }

    /**
     * Does an install-folder name look like Java MIN_JAVA_MAJOR or newer?
     * Decided by the FIRST number in the name, so "jdk1.8.0_292" reads as 1
     * (too old) while "jdk25.0.3_9", "jdk-26" and "temurin-25.jre" pass.
     * Name-based only — callers still verify bin/java actually exists.
     */
    static boolean dirLooksLikeJavaAtLeastMin(String dirName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(dirName);
        if (!m.find()) return false;
        try {
            return Integer.parseInt(m.group(1)) >= MIN_JAVA_MAJOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String findJava25Path() {
        if (isMac()) return findJava25Mac();
        if (isWindows()) return findJava25Windows();
        return findJava25Generic();
    }

    /**
     * Locate Java 25+ on macOS. Prefers Apple's own JVM registry (java_home),
     * which correctly finds any properly-installed JDK/JRE regardless of
     * vendor (Temurin, Corretto, Zulu, ...) or install method (pkg, brew
     * cask). Falls back to scanning the standard JVM bundle directory.
     */
    private static String findJava25Mac() {
        try {
            Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", "25+").start();
            String home = readProcessOutput(p).trim();
            int exit = p.waitFor();
            if (exit == 0 && !home.isEmpty()) {
                File javaBin = new File(home, "bin/java");
                if (javaBin.exists()) return javaBin.getAbsolutePath();
            }
        } catch (Exception e) {
            System.err.println("[Launcher] java_home lookup failed: " + e.getMessage());
        }

        File jvmDir = new File("/Library/Java/JavaVirtualMachines");
        File[] bundles = jvmDir.listFiles();
        if (bundles != null) {
            for (File bundle : bundles) {
                if (dirLooksLikeJavaAtLeastMin(bundle.getName())) {
                    File javaBin = new File(bundle, "Contents/Home/bin/java");
                    if (javaBin.exists()) return javaBin.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String findJava25Windows() {
        String[] basePaths = {
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\BellSoft",
            "C:\\Program Files\\Microsoft"
        };
        for (String base : basePaths) {
            File dir = new File(base);
            if (!dir.exists()) continue;
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File d : children) {
                if (!d.isDirectory()) continue;
                // Accept any install folder whose version reads >= 25
                // (jdk25.0.3_9, jdk-26, LibericaJDK-25, ...) — the bin
                // check below filters out non-Java folders with numbers.
                if (dirLooksLikeJavaAtLeastMin(d.getName())) {
                    File javaw = new File(d, "bin" + File.separator + "javaw.exe");
                    if (javaw.exists()) return javaw.getAbsolutePath();
                    File java = new File(d, "bin" + File.separator + "java.exe");
                    if (java.exists()) return java.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /** Best-effort lookup for other OSes (e.g. Linux) — JAVA_HOME, then /usr/lib/jvm. */
    private static String findJava25Generic() {
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null) {
            File javaBin = new File(javaHomeEnv, "bin/java");
            if (javaBin.exists()) return javaBin.getAbsolutePath();
        }
        File jvmDir = new File("/usr/lib/jvm");
        File[] children = jvmDir.listFiles();
        if (children != null) {
            for (File d : children) {
                if (dirLooksLikeJavaAtLeastMin(d.getName())) {
                    File javaBin = new File(d, "bin/java");
                    if (javaBin.exists()) return javaBin.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String readProcessOutput(Process p) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            br.close();
        }
    }

    private static boolean relaunchWithJava(String javaPath) {
        try {
            String jarPath = new File(JavaFinder.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                javaPath, "-Dfile.encoding=UTF-8", "-jar", jarPath
            );
            pb.directory(new File(jarPath).getParentFile());
            pb.start();
            return true;
        } catch (Exception e) {
            System.err.println("[Launcher] Relaunch failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}