package com.xinl.easyclaw.hub.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinl.easyclaw.hub.contract.auth.LoginRequest;
import com.xinl.easyclaw.hub.contract.auth.TokenResponse;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * hub 集成测试基座：整上下文 + MockMvc + 临时 SQLite 文件库（本机无 Docker/PG 的过渡方案；
 * 实体映射与生产 PG DDL 的口径一致性由 db/migration/V1__init_core.sql 保证，备 PG 后换 Testcontainers）。
 * 各测试类使用独立用户名/ slug 前缀，避免共享 Spring 上下文（同一库）下数据相互干扰。
 * 注意：不要断言中文错误文案（MockMvc 默认字符集会乱码），断言稳定错误码。
 * 用户开通：公开注册已取消，测试直接落库建用户（等价于平台管理员开通后完成改密的状态）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/hub-it-${random.uuid}.db?busy_timeout=10000",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.community.dialect.SQLiteDialect",
        "spring.flyway.enabled=false",
        "hub.jwt.secret=integration-test-secret-key-0123456789abcdef",
        "hub.jwt.access-ttl-minutes=30",
        "hub.jwt.refresh-ttl-days=30"
})
@AutoConfigureMockMvc
public abstract class HubIntegrationTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    // ---------- HTTP 辅助 ----------

    protected ResultActions postJson(String url, Object body, String accessToken) throws Exception {
        return mvc.perform(withAuth(post(url), accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    protected ResultActions patchJson(String url, Object body, String accessToken) throws Exception {
        return mvc.perform(withAuth(patch(url), accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    protected ResultActions putJson(String url, Object body, String accessToken) throws Exception {
        return mvc.perform(withAuth(put(url), accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)));
    }

    protected ResultActions getJson(String url, String accessToken) throws Exception {
        return mvc.perform(withAuth(get(url), accessToken));
    }

    protected ResultActions deleteJson(String url, String accessToken) throws Exception {
        return mvc.perform(withAuth(delete(url), accessToken));
    }

    private static MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String accessToken) {
        return accessToken == null ? builder : builder.header("Authorization", "Bearer " + accessToken);
    }

    // ---------- 业务辅助 ----------

    /** 直接落库创建活跃用户（mustChangePassword=false），返回 userId。 */
    protected long createUserOk(String username, String email, String password) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setEmail(email);
        u.setDisplayName(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(u);
        return u.getId();
    }

    /** 直接落库创建首登待改密用户（等价平台管理员开通后的初始状态）。 */
    protected long createUserMustChangeOk(String username, String email, String password) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setEmail(email);
        u.setDisplayName(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setMustChangePassword(true);
        userRepository.save(u);
        return u.getId();
    }

    /** 直接落库创建平台管理员。 */
    protected long createPlatformAdminOk(String username, String password) {
        UserEntity u = new UserEntity();
        u.setUsername(username);
        u.setDisplayName(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setPlatformAdmin(true);
        userRepository.save(u);
        return u.getId();
    }

    /** 登录并断言 200，返回令牌对。 */
    protected TokenResponse loginOk(String usernameOrEmail, String password) throws Exception {
        String json = postJson("/api/auth/login", new LoginRequest(usernameOrEmail, password), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readValue(json, TokenResponse.class);
    }

    /** 建用户 + 登录一步完成，返回 access token。 */
    protected String createUserAndLogin(String username, String password) throws Exception {
        createUserOk(username, null, password);
        return loginOk(username, password).accessToken();
    }
}
