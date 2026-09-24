package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.provider.CreditBalanceDto;
import com.xinl.easyclaw.hub.contract.provider.CreditGrantSummaryDto;
import com.xinl.easyclaw.hub.contract.provider.CreditUsageDto;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.ProviderGrantService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分使用情况端点（V27）：
 * - GET /api/me/credits：我的积分余额（按 provider 一行，含每日/每月/每年/临时构成）
 * - GET /api/me/credit-usages：我的使用记录（模型、消耗分值，倒序分页）
 * - GET /api/orgs/{orgId}/credit-overview：组织积分总览（owner/admin）
 * - GET /api/platform/credit-overview：平台共享池积分总览（platformAdmin）
 */
@RestController
@RequestMapping("/api")
public class CreditController {

    private final ProviderGrantService grantService;

    public CreditController(ProviderGrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping("/me/credits")
    public List<CreditBalanceDto> myCredits() {
        return grantService.myCreditBalances(CurrentUserHolder.requireUserId());
    }

    @GetMapping("/me/credit-usages")
    public List<CreditUsageDto> myCreditUsages(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return grantService.myCreditUsages(CurrentUserHolder.requireUserId(), page, size);
    }

    @GetMapping("/orgs/{orgId}/credit-overview")
    public List<CreditGrantSummaryDto> orgCreditOverview(@PathVariable Long orgId) {
        return grantService.orgCreditOverview(CurrentUserHolder.requireUserId(), orgId);
    }

    @GetMapping("/platform/credit-overview")
    public List<CreditGrantSummaryDto> platformCreditOverview() {
        return grantService.platformCreditOverview(CurrentUserHolder.requireUserId());
    }
}
