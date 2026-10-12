package com.spaceos.os;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * NETTOOLS :: network reconnaissance built into the shell.
 *
 * WHY NOT REAL NMAP. Kali's nmap is an ELF linked against glibc — libc.so.6,
 * libpcap, libssl, liblua. Android is Bionic; it has libc.so and no glibc at
 * all, so that binary cannot load here no matter where in the APK it is
 * placed. Building nmap for Android means cross-compiling it and every
 * dependency against the NDK, and there is no aarch64 NDK to build with.
 *
 * Shipping a binary that cannot run would be worse than not shipping one. So
 * the useful parts — TCP scanning, host discovery, the local socket and ARP
 * tables, DNS, HTTP probing — are implemented here directly. They run
 * unprivileged, which rules out SYN scans and raw sockets, but a TCP connect
 * scan needs no privileges and tells you the same thing about open ports.
 */
public final class NetTools {

    private NetTools() {}

    /** Ports worth trying when none are named. */
    private static final int[] COMMON = {
            21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445, 993, 995,
            1723, 3306, 3389, 5432, 5900, 8080, 8443, 8888, 9100
    };

    private static String service(int port) {
        switch (port) {
            case 21: return "ftp";        case 22: return "ssh";
            case 23: return "telnet";     case 25: return "smtp";
            case 53: return "domain";     case 80: return "http";
            case 110: return "pop3";      case 111: return "rpcbind";
            case 135: return "msrpc";     case 139: return "netbios-ssn";
            case 143: return "imap";      case 443: return "https";
            case 445: return "microsoft-ds";
            case 993: return "imaps";     case 995: return "pop3s";
            case 1723: return "pptp";     case 3306: return "mysql";
            case 3389: return "ms-wbt";   case 5432: return "postgresql";
            case 5900: return "vnc";      case 8080: return "http-alt";
            case 8443: return "https-alt";case 8888: return "http-alt";
            case 9100: return "jetdirect";
            default: return "unknown";
        }
    }

    /** Every built-in the shell recognises. */
    public static boolean isBuiltin(String cmd) {
        String c = firstWord(cmd);
        return c.equals("scan") || c.equals("sweep") || c.equals("ports")
                || c.equals("arp") || c.equals("dns") || c.equals("probe")
                || c.equals("netinfo") || c.equals("tools");
    }

    /** Runs a built-in. Blocking — the terminal calls this off the UI thread. */
    public static String run(Context ctx, String cmd) {
        String c = firstWord(cmd);
        String rest = cmd.substring(c.length()).trim();
        try {
            if (c.equals("tools")) return help();
            if (c.equals("scan")) return scan(rest);
            if (c.equals("sweep")) return sweep(ctx, rest);
            if (c.equals("ports")) return listeningPorts();
            if (c.equals("arp")) return arpTable();
            if (c.equals("dns")) return dns(rest);
            if (c.equals("probe")) return probe(rest);
            if (c.equals("netinfo")) return netinfo(ctx);
        } catch (Throwable t) {
            return "error: " + t.getClass().getSimpleName() + " " + t.getMessage();
        }
        return "unknown built-in: " + c;
    }

    public static String help() {
        return "SPACE OS NETWORK TOOLS  (built in, unprivileged)\n"
             + "\n"
             + "  scan <host> [ports]   TCP connect scan. ports as 22,80 or 1-1024\n"
             + "  sweep [cidr]          find live hosts; defaults to your own /24\n"
             + "  ports                 local listening sockets, from /proc/net/tcp\n"
             + "  arp                   neighbour table, from /proc/net/arp\n"
             + "  dns <name|ip>         forward and reverse resolve\n"
             + "  probe <url>           HTTP status and response headers\n"
             + "  netinfo               interfaces, addresses and routes\n"
             + "\n"
             + "Real nmap is a glibc ELF and cannot execute on Android's Bionic\n"
             + "libc, so these are native implementations rather than a wrapper.\n"
             + "SYN scans and OS fingerprinting need raw sockets and root; a TCP\n"
             + "connect scan needs neither and finds the same open ports.\n";
    }

    // =====================================================================
    // SCAN
    // =====================================================================

    private static String scan(String args) throws Exception {
        if (args.length() == 0) return "usage: scan <host> [ports]";
        String[] parts = args.split("\\s+");
        String host = parts[0];
        int[] ports = parts.length > 1 ? parsePorts(parts[1]) : COMMON;
        if (ports.length == 0) return "no valid ports";
        if (ports.length > 4096) return "too many ports; keep it under 4096";

        final InetAddress addr;
        try { addr = InetAddress.getByName(host); }
        catch (Throwable t) { return "cannot resolve " + host; }

        StringBuilder out = new StringBuilder();
        out.append("scanning ").append(addr.getHostAddress())
           .append("  ").append(ports.length).append(" ports\n\n");

        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(64);
        List<Future<String>> jobs = new ArrayList<Future<String>>();
        for (final int p : ports) {
            jobs.add(pool.submit(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(addr, p), 700);
                        return String.format(Locale.US, "  %-7d open    %s", p, service(p));
                    } catch (Throwable t) {
                        return null;      // closed or filtered
                    } finally {
                        try { s.close(); } catch (Throwable ignored) {}
                    }
                }
            }));
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int open = 0;
        for (Future<String> f : jobs) {
            String line = f.get();
            if (line != null) { out.append(line).append('\n'); open++; }
        }
        if (open == 0) out.append("  no open ports found\n");
        out.append("\n").append(open).append(" open of ").append(ports.length)
           .append(" in ").append(System.currentTimeMillis() - t0).append("ms\n");
        return out.toString();
    }

    private static int[] parsePorts(String spec) {
        List<Integer> list = new ArrayList<Integer>();
        for (String chunk : spec.split(",")) {
            chunk = chunk.trim();
            if (chunk.length() == 0) continue;
            int dash = chunk.indexOf('-');
            try {
                if (dash > 0) {
                    int a = Integer.parseInt(chunk.substring(0, dash).trim());
                    int b = Integer.parseInt(chunk.substring(dash + 1).trim());
                    for (int p = Math.min(a, b); p <= Math.max(a, b); p++) {
                        if (p > 0 && p < 65536) list.add(p);
                    }
                } else {
                    int p = Integer.parseInt(chunk);
                    if (p > 0 && p < 65536) list.add(p);
                }
            } catch (Throwable ignored) {}
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    // =====================================================================
    // SWEEP
    // =====================================================================

    private static String sweep(Context ctx, String args) throws Exception {
        String base = args.length() > 0 ? args : Sys.ipv4();
        if (base == null || base.equals("--")) return "no local address; are you on wifi?";
        base = base.replaceAll("/\\d+$", "");
        int dot = base.lastIndexOf('.');
        if (dot < 0) return "cannot work out the subnet from " + base;
        final String prefix = base.substring(0, dot + 1);

        StringBuilder out = new StringBuilder();
        out.append("sweeping ").append(prefix).append("0/24\n\n");
        long t0 = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(96);
        List<Future<String>> jobs = new ArrayList<Future<String>>();
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            jobs.add(pool.submit(new java.util.concurrent.Callable<String>() {
                @Override public String call() {
                    try {
                        InetAddress a = InetAddress.getByName(ip);
                        // isReachable uses ICMP where permitted and falls back
                        // to a TCP echo attempt; then try common ports so
                        // hosts that drop ping still show up.
                        if (a.isReachable(600)) return ip + "   up";
                        for (int p : new int[]{80, 443, 22, 445, 8080}) {
                            Socket s = new Socket();
                            try {
                                s.connect(new InetSocketAddress(a, p), 350);
                                return ip + "   up   (tcp/" + p + ")";
                            } catch (Throwable ignored) {
                            } finally { try { s.close(); } catch (Throwable ig) {} }
                        }
                    } catch (Throwable ignored) {}
                    return null;
                }
            }));
        }
        pool.shutdown();
        pool.awaitTermination(90, TimeUnit.SECONDS);

        int live = 0;
        for (Future<String> f : jobs) {
            String line = f.get();
            if (line != null) { out.append("  ").append(line).append('\n'); live++; }
        }
        out.append("\n").append(live).append(" host")
           .append(live == 1 ? "" : "s").append(" up in ")
           .append(System.currentTimeMillis() - t0).append("ms\n");
        if (live <= 1) {
            out.append("\nnote: many devices drop ICMP and answer no common port.\n");
        }
        return out.toString();
    }

    // =====================================================================
    // LOCAL STATE
    // =====================================================================

    /** /proc/net/tcp is readable and lists this device's own sockets. */
    private static String listeningPorts() {
        StringBuilder out = new StringBuilder("local sockets\n\n");
        out.append(readSockets("/proc/net/tcp", "tcp"));
        out.append(readSockets("/proc/net/tcp6", "tcp6"));
        if (out.length() < 30) {
            out.append("  nothing readable — Android restricts /proc/net on newer\n"
                     + "  releases unless the app owns the socket\n");
        }
        return out.toString();
    }

    private static String readSockets(String path, String label) {
        StringBuilder out = new StringBuilder();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line = r.readLine();     // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 4) continue;
                String[] local = f[1].split(":");
                if (local.length != 2) continue;
                int port = Integer.parseInt(local[1], 16);
                String state = f[3];
                if (!state.equals("0A")) continue;      // 0A = LISTEN
                out.append(String.format(Locale.US, "  %-6s %-7d listening   %s\n",
                        label, port, service(port)));
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        return out.toString();
    }

    private static String arpTable() {
        StringBuilder out = new StringBuilder("neighbour table\n\n");
        BufferedReader r = null;
        int n = 0;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line = r.readLine();     // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 6) continue;
                if (f[3].equals("00:00:00:00:00:00")) continue;
                out.append(String.format(Locale.US, "  %-16s %-18s %s\n",
                        f[0], f[3], f[5]));
                n++;
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
        if (n == 0) {
            out.append("  empty or unreadable. run a sweep first to populate it,\n"
                     + "  and note Android hides /proc/net/arp on some builds\n");
        }
        return out.toString();
    }

    // =====================================================================
    // DNS AND HTTP
    // =====================================================================

    private static String dns(String name) {
        if (name.length() == 0) return "usage: dns <name|ip>";
        StringBuilder out = new StringBuilder();
        try {
            InetAddress[] all = InetAddress.getAllByName(name);
            out.append(name).append("\n\n");
            for (InetAddress a : all) {
                out.append("  ").append(a.getHostAddress());
                String host = a.getCanonicalHostName();
                if (host != null && !host.equals(a.getHostAddress())) {
                    out.append("   ").append(host);
                }
                out.append('\n');
            }
        } catch (Throwable t) {
            return "cannot resolve " + name;
        }
        return out.toString();
    }

    private static String probe(String url) {
        if (url.length() == 0) return "usage: probe <url>";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);

            int code = conn.getResponseCode();
            StringBuilder out = new StringBuilder();
            out.append(url).append("\n\n  HTTP ").append(code).append("  ")
               .append(conn.getResponseMessage()).append("\n\n");

            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                out.append("  ").append(e.getKey()).append(": ");
                out.append(join(e.getValue())).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return "probe failed: " + t.getMessage();
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String join(List<String> v) {
        StringBuilder sb = new StringBuilder();
        for (String s : v) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    private static String netinfo(Context ctx) {
        StringBuilder out = new StringBuilder("interfaces\n\n");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                out.append("  ").append(ni.getName());
                if (ni.isLoopback()) out.append("  (loopback)");
                out.append('\n');
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    out.append("      ").append(a.getHostAddress()).append('\n');
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    StringBuilder m = new StringBuilder();
                    for (byte b : mac) {
                        if (m.length() > 0) m.append(':');
                        m.append(String.format(Locale.US, "%02x", b));
                    }
                    out.append("      mac ").append(m).append('\n');
                }
            }
        } catch (Throwable t) {
            out.append("  ").append(t.getMessage()).append('\n');
        }

        Sys.Link l = Sys.link(ctx);
        out.append("\nuplink\n\n");
        out.append("  transport  ").append(l.type).append('\n');
        out.append("  ssid       ").append(l.ssid).append('\n');
        out.append("  address    ").append(l.ip).append('\n');
        out.append("  signal     ").append(l.rssi == 0 ? "--" : l.rssi + " dBm").append('\n');
        return out.toString();
    }

    private static String firstWord(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        return (sp < 0 ? t : t.substring(0, sp)).toLowerCase(Locale.US);
    }
}
