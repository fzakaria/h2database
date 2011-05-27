/*
 * Copyright 2004-2008 H2 Group. Multiple-Licensed under the H2 License, 
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.h2.engine.Constants;
import org.h2.server.web.ConnectionInfo;
import org.h2.util.ClassUtils;
import org.h2.util.FileUtils;
import org.h2.util.JdbcDriverUtils;
import org.h2.util.JdbcUtils;

/**
 * Interactive command line tool to access a database using JDBC.
 */
public class Shell {

    PrintStream out = System.out;
    PrintStream err = System.err;
    private InputStream in = System.in;
    private Connection conn;
    private Statement stat;
    private boolean listMode;
    private int maxColumnSize = 100;
    // Windows: '\u00b3';
    private char boxVertical = '|'; 
    private BufferedReader reader;
    
    /**
     * The command line interface for this tool. The options must be split into
     * strings like this: "-user", "sa",... Options are case sensitive. The
     * following options are supported:
     * <ul>
     * <li>-help or -? (print the list of options) </li>
     * <li>-url jdbc:h2:... (database URL) </li>
     * <li>-user username </li>
     * <li>-password password </li>
     * <li>-driver driver the JDBC driver class name (not required for most
     * databases) </li>
     * </ul>
     * 
     * @param args the command line arguments
     * @throws SQLException
     */
    public static void main(String[] args) throws SQLException {
        new Shell().run(args);
    }
    
    private void showUsage() {
        println("An interactive command line database tool.");
        println("java "+getClass().getName() + "\n" +
                " [-url <url>]       The database URL\n" +
                " [-user <user>]     The user name\n" +
                " [-password <pwd>]  The password\n" +
                " [-driver <class>]  The JDBC driver class to use (not required in most cases)");
        println("See also http://h2database.com/javadoc/" + getClass().getName().replace('.', '/') + ".html");
    }
    
    /**
     * Redirects the input and output. By default, System.in, out and err are
     * used.
     * 
     * @param in the input stream to use
     * @param out the output stream to use
     * @param err the output error stream to use
     */
    public void setStreams(InputStream in, PrintStream out, PrintStream err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }
    
    /**
     * Run the shell tool with the given command line settings.
     * 
     * @param args the command line settings
     */
    public void run(String[] args) throws SQLException {
        String url = null;
        String user = "";
        String password = "";
        for (int i = 0; args != null && i < args.length; i++) {
            if (args[i].equals("-url")) {
                url = args[++i];
            } else if (args[i].equals("-user")) {
                user = args[++i];
            } else if (args[i].equals("-password")) {
                password = args[++i];
            } else if (args[i].equals("-driver")) {
                String driver = args[++i];
                ClassUtils.loadUserClass(driver);
            } else {
                println("Unsupported option: " + args[i]);
                showUsage();
                return;
            }
        }
        if (url != null) {
            org.h2.Driver.load();
            conn = DriverManager.getConnection(url, user, password);
            stat = conn.createStatement();
        }
        promptLoop();
    }
    
    private void showHelp() {
        println("Commands are case insensitive; SQL statements end with ';'");
        println("help or ?      Display this help");
        println("list           Toggle result list mode");
        println("maxwidth       Set maximum column width (default is 100)");
        println("show           List all tables");
        println("describe       Describe a table");
        println("quit or exit   Close the connection and exit");
        println("");
    }

    private void promptLoop() {
        println("");
        println("Welcome to H2 Shell " + Constants.getFullVersion());
        println("Exit with Ctrl+C");
        if (conn != null) {
            showHelp();
        }
        String statement = null;
        reader = new BufferedReader(new InputStreamReader(in));
        while (true) {
            try {
                if (conn == null) {
                    connect();
                    showHelp();
                }
                if (statement == null) {
                    print("sql> ");
                } else {
                    print("...> ");
                }
                String line = readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                boolean end = line.endsWith(";");
                if (end) {
                    line = line.substring(0, line.length() - 1).trim();
                }
                String upper = line.toUpperCase();
                if ("EXIT".equals(upper) || "QUIT".equals(upper)) {
                    break;
                } else if ("HELP".equals(upper) || "?".equals(upper)) {
                    showHelp();
                } else if ("LIST".equals(upper)) {
                    listMode = !listMode;
                    println("Result list mode is now " + (listMode ? "on" : "off"));
                } else if (upper.startsWith("DESCRIBE")) {
                    String tableName = upper.substring("DESCRIBE".length()).trim();
                    if (tableName.length() == 0) {
                        println("Usage: describe [<schema name>.]<table name>");
                    } else {
                        String schemaName = null;
                        int dot = tableName.indexOf('.');
                        if (dot >= 0) {
                            schemaName = tableName.substring(0, dot);
                            tableName = tableName.substring(dot + 1);
                        }
                        PreparedStatement prep = null;
                        ResultSet rs = null;
                        try {
                            String sql = "SELECT CAST(COLUMN_NAME AS VARCHAR(32)) \"Column Name\", " + 
                                "CAST(TYPE_NAME AS VARCHAR(14)) \"Type\", " + 
                                "NUMERIC_PRECISION \"Precision\", " + 
                                "CAST(IS_NULLABLE AS VARCHAR(8)) \"Nullable\", " + 
                                "CAST(COLUMN_DEFAULT AS VARCHAR(20)) \"Default\" " + 
                                "FROM INFORMATION_SCHEMA.COLUMNS " + 
                                "WHERE UPPER(TABLE_NAME)=?";
                            if (schemaName != null) {
                                sql += " AND UPPER(TABLE_SCHEMA)=?";
                            }
                            sql += " ORDER BY ORDINAL_POSITION";
                            prep = conn.prepareStatement(sql);
                            prep.setString(1, tableName.toUpperCase());
                            if (schemaName != null) {
                                prep.setString(2, schemaName.toUpperCase());
                            }
                            rs = prep.executeQuery();
                            printResult(rs, false);
                        } catch (SQLException e) {
                            println("Exception: " + e.toString());
                            e.printStackTrace(err);
                        } finally {
                            JdbcUtils.closeSilently(rs);
                            JdbcUtils.closeSilently(prep);
                        }
                    }
                } else if (upper.startsWith("SHOW")) {
                    ResultSet rs = null;
                    try {
                        rs = stat.executeQuery(
                                "SELECT CAST(TABLE_SCHEMA AS VARCHAR(32)) \"Schema\", TABLE_NAME \"Table Name\" " +
                                "FROM INFORMATION_SCHEMA.TABLES ORDER BY TABLE_SCHEMA, TABLE_NAME");
                        printResult(rs, false);
                    } catch (SQLException e) {
                        println("Exception: " + e.toString());
                        e.printStackTrace(err);
                    } finally {
                        JdbcUtils.closeSilently(rs);
                    }
                } else if (upper.startsWith("MAXWIDTH")) {
                    upper = upper.substring("MAXWIDTH".length()).trim();
                    try {
                        maxColumnSize = Integer.parseInt(upper);
                    } catch (NumberFormatException e) {
                        println("Usage: maxwidth <integer value>");
                    }
                    println("Maximum column width is now " + maxColumnSize);
                } else {
                    if (statement == null) {
                        statement = line;
                    } else {
                        statement += " " + line;
                    }
                    if (end) {
                        execute(statement);
                        statement = null;
                    }
                }
            } catch (SQLException e) {
                println("SQL Exception: " + e.getMessage());
                statement = null;
            } catch (IOException e) {
                println(e.getMessage());
                break;
            } catch (Exception e) {
                println("Exception: " + e.toString());
                e.printStackTrace(err);
                break;
            }
        }
        if (conn != null) {
            try {
                conn.close();
                println("Connection closed");
            } catch (SQLException e) {
                println("SQL Exception: " + e.getMessage());
                e.printStackTrace(err);
            }
        }
    }

    private void connect() throws IOException, SQLException {
        String propertiesFileName = FileUtils.getFileInUserHome(Constants.SERVER_PROPERTIES_FILE);
        String url = "jdbc:h2:~/test";
        String user = "sa";
        String driver = null;
        try {
            Properties prop = FileUtils.loadProperties(propertiesFileName);
            String data = null;
            boolean found = false;
            for (int i = 0;; i++) {
                String d = prop.getProperty(String.valueOf(i));
                if (d == null) {
                    break;
                }
                found = true;
                data = d;
            }
            if (found) {
                ConnectionInfo info = new ConnectionInfo(data);
                url = info.url;
                user = info.user;
                driver = info.driver;
            }
        } catch (IOException e) {
            // ignore
        }
        println("[Enter]   " + url);
        print("URL       ");
        url = readLine(url);
        if (driver == null) {
            driver = JdbcDriverUtils.getDriver(url);
        }
        if (driver != null) {
            println("[Enter]   " + driver);
        }
        print("Driver    ");
        driver = readLine(driver);
        println("[Enter]   " + user);
        print("User      ");
        user = readLine(user);
        println("[Enter]   Hide");
        print("Password  ");
        String password = readLine();
        if (password.length() == 0) {
            password = readPassword();
        }
        conn = JdbcUtils.getConnection(driver, url, user, password);
        stat = conn.createStatement();
        println("Connected");
    }
    
    private void print(String s) {
        out.print(s);
        out.flush();
    }
    
    private void println(String s) {
        out.println(s);
        out.flush();
    }

    private String readPassword() throws IOException {
        try {
            Method getConsole = System.class.getMethod("console", new Class[0]);
            Object console = getConsole.invoke(null, (Object[]) null);
            Method readPassword = console.getClass().getMethod("readPassword", new Class[0]);
            print("Password  ");
            char[] password = (char[]) readPassword.invoke(console, (Object[]) null);
            return password == null ? null : new String(password);
        } catch (Exception e) {
            // ignore, use the default solution
        }
        /**
         * This thread hides the password by repeatedly printing 
         * backspace, backspace, &gt;, &lt;.
         */
        class PasswordHider extends Thread {
            volatile boolean stop;
            public void run() {
                while (!stop) {
                    out.print("\b\b><");
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }
        PasswordHider thread = new PasswordHider();
        thread.start();
        print("Password  > ");
        String p = readLine();
        thread.stop = true;
        try {
            thread.join();
        } catch (InterruptedException e) {
            // ignore
        }
        print("\b\b");
        return p;
    }
    
    private String readLine(String defaultValue) throws IOException {
        String s = readLine();
        return s.length() == 0 ? defaultValue : s;
    }

    private String readLine() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Aborted");
        }
        return line;
    }
    
    private void execute(String sql) {
        long time = System.currentTimeMillis();
        boolean result;
        try {
            result = stat.execute(sql);
        } catch (SQLException e) {
            println("Error: " + e.toString());
            return;
        }
        ResultSet rs = null;
        try {
            if (result) {
                rs = stat.getResultSet();
                int rowCount = printResult(rs, listMode);
                time = System.currentTimeMillis() - time;
                println("(" + rowCount + (rowCount == 1 ? " row, " : " rows, ") + time + " ms)");
            } else {
                int updateCount = stat.getUpdateCount();
                time = System.currentTimeMillis() - time;
                println("(Update count: " + updateCount + ", " + time + " ms)");
            }
        } catch (SQLException e) {
            println("Error: " + e.toString());
            e.printStackTrace(err);
        } finally {
            JdbcUtils.closeSilently(rs);
        }
    }
    
    private int printResult(ResultSet rs, boolean listMode) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int longest = 0;
        int len = meta.getColumnCount();
        String[] columns = new String[len];
        int[] columnSizes = new int[len];
        int total = 0;
        for (int i = 0; i < len; i++) {
            String s = meta.getColumnLabel(i + 1);
            int l = s.length();
            if (!listMode) {
                l = Math.max(l, meta.getColumnDisplaySize(i + 1));
                l = Math.min(maxColumnSize, l);
            }
            if (s.length() > l) {
                s = s.substring(0, l);
            }
            columns[i] = s;
            columnSizes[i] = l;
            longest = Math.max(longest, l);
            total += l;
        }
        StringBuffer buff = new StringBuffer();
        if (!listMode) {
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    buff.append(boxVertical);
                }
                String s = columns[i];
                buff.append(s);
                if (i < len - 1) {
                    for (int j = s.length(); j < columnSizes[i]; j++) {
                        buff.append(' ');
                    }
                }
            }
            println(buff.toString());
        }
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            buff.setLength(0);
            if (listMode) {
                if (rowCount > 1) {
                    println("");
                }
                for (int i = 0; i < len; i++) {
                    if (i > 0) {
                        buff.append('\n');
                    }
                    String label = columns[i];
                    buff.append(label);
                    for (int j = label.length(); j < longest; j++) {
                        buff.append(' ');
                    }
                    buff.append(": ");
                    buff.append(rs.getString(i + 1));
                }
            } else {
                for (int i = 0; i < len; i++) {
                    if (i > 0) {
                        buff.append(boxVertical);
                    }
                    String s = rs.getString(i + 1);
                    if (s == null) {
                        s = "null";
                    }
                    int m = columnSizes[i];
                    if (!listMode && s.length() > m) {
                        s = s.substring(0, m);
                    }
                    buff.append(s);
                    if (i < len - 1) {
                        for (int j = s.length(); j < m; j++) {
                            buff.append(' ');
                        }
                    }
                }
            }
            println(buff.toString());
        }
        if (rowCount == 0 && listMode) {
            for (int i = 0; i < len; i++) {
                String label = columns[i];
                buff.append(label);
                buff.append('\n');
            }
            println(buff.toString());
        }
        return rowCount;
    }

}