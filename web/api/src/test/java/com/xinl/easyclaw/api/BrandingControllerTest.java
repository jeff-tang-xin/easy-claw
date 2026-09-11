package com.xinl.easyclaw.api;

import com.xinl.easyclaw.config.BrandingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link BrandingController} 品牌下发接口测试。
 * 守两条：① 默认值必须与旧版硬编码一致（未配置 yml 的用户界面零变化）；
 * ② 自定义配置原样下发。
 */
class BrandingControllerTest {

    private MockMvc mvcWith(BrandingProperties props) {
        return MockMvcBuilders.standaloneSetup(new BrandingController(props)).build();
    }

    @Test
    void 默认值与旧版硬编码一致() throws Exception {
        mvcWith(new BrandingProperties())
                .perform(get("/api/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Easy-Claw"))
                .andExpect(jsonPath("$.subtitle").value("AI 编程助手"))
                .andExpect(jsonPath("$.icon").value("🦞"));
    }

    @Test
    void 自定义配置原样下发() throws Exception {
        BrandingProperties props = new BrandingProperties();
        props.setName("我的助手");
        props.setSubtitle("内部工具");
        props.setIcon("/my-logo.png");
        mvcWith(props)
                .perform(get("/api/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("我的助手"))
                .andExpect(jsonPath("$.subtitle").value("内部工具"))
                .andExpect(jsonPath("$.icon").value("/my-logo.png"));
    }
}
