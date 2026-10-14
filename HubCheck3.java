
import java.sql.*;
public class HubCheck3 {
    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://39.96.116.199:5432/easy_claw", args[0], args[1])) {
            q(c, "app_keys cols", "SELECT column_name FROM information_schema.columns WHERE table_schema='hub' AND table_name='app_keys' ORDER BY ordinal_position");
            q(c, "app_keys", "SELECT id, name, org_id, created_by FROM hub.app_keys ORDER BY id");
            q(c, "projects", "SELECT id, name, org_id FROM hub.projects ORDER BY id");
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
