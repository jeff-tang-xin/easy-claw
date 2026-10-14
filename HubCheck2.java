
import java.sql.*;
public class HubCheck2 {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://39.96.116.199:5432/easy_claw", args[0], args[1])) {
            q(c, "tables", "SELECT tablename FROM pg_tables WHERE schemaname='hub' ORDER BY tablename");
            q(c, "workspaces", "SELECT id, name, project_id, org_id FROM hub.workspaces ORDER BY id");
        }
    }
    static void q(Connection c, String t, String sql) {
        System.out.println("==== " + t + " ====");
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder h = new StringBuilder();
            for (int i = 1; i <= n; i++) h.append(md.getColumnName(i)).append(" | ");
            System.out.println(h);
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) b.append(rs.getString(i)).append(" | ");
                System.out.println(b);
            }
        } catch (Exception e) { System.out.println("ERROR: " + e.getMessage()); }
        System.out.println();
    }
}
