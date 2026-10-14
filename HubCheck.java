import java.sql.*;

public class HubCheck {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://39.96.116.199:5432/easy_claw";
        try (Connection c = DriverManager.getConnection(url, args[0], args[1])) {
            q(c, "flyway latest", "SELECT version, description, success, installed_on FROM hub.flyway_schema_history ORDER BY installed_rank DESC LIMIT 4");
            q(c, "db_connections", "SELECT id, server_key, enabled, org_id FROM hub.db_connections ORDER BY id");
            q(c, "ops_servers", "SELECT id, server_key, enabled, org_id FROM hub.ops_servers ORDER BY id");
            q(c, "project_resources", "SELECT resource_type, resource_id, project_id FROM hub.project_resources ORDER BY id");
            q(c, "resource_grants", "SELECT resource_type, resource_id, user_id, valid_from, valid_until FROM hub.resource_grants ORDER BY id");
            q(c, "appkeys", "SELECT id, name, org_id FROM hub.appkeys ORDER BY id");
            q(c, "ops_server_grants", "SELECT server_id, user_id, valid_from, valid_until FROM hub.ops_server_grants ORDER BY server_id");
        }
    }
    static void q(Connection c, String title, String sql) {
        System.out.println("==== " + title + " ====");
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
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
        System.out.println();
    }
}
