package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.finance.config.RepaymentEngineConfiguration;
import com.loanrun.report.service.StrategyEngineInputs;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import com.loanrun.risk.domain.*;
import com.loanrun.risk.service.RiskPayoffProjection;
import com.loanrun.risk.service.RiskRateStress;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Real Team 1 arithmetic through the unchanged Team 2 adapters; no mocked engine. */
class EngineAdapterIntegrationTest {
    final YearMonth month = YearMonth.of(2026, 1);
    final Clock clock = Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    final LocalDate asOf = LocalDate.now(clock);

    PlanSnapshot plan(long id, long extra, long saving, long required) {
        return new PlanSnapshot(id, true, LocalDate.of(2026, 12, 25), null, extra, saving, required,
            "MATURITY", null, asOf, 1);
    }
    RiskScenarioInput source(List<LoanTerms> terms, List<PlanSnapshot> plans, long available,
            Map<Long, Long> paid, Map<Long, Long> saved) {
        var capacity = new BudgetCapacity(month, available, available, 0, available, 0, 0, available,
            0, 0L, "FORECAST", "OPEN", List.of());
        var inputs = new RiskInputs(7, asOf, month, terms, plans, null, capacity, null, 0L, 0L, null);
        return new RiskScenarioInput(inputs, new PaymentSummary(month, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, paid),
            new SavingSummary(month, 0, 0, 0, 0, true, 0, saved));
    }

    @Test void productionBeanIsUniqueAndRateProbeChangesOnlyVariableLoansByAnAbsoluteRate() {
        try (var context = new AnnotationConfigApplicationContext(RepaymentEngineConfiguration.class)) {
            assertThat(context.getBeansOfType(RepaymentEngine.class)).hasSize(1);
            assertThat(context.getBean(RepaymentEngine.class)).isInstanceOf(DefaultRepaymentEngine.class);
            var variable = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); variable.rateType = RateType.VARIABLE;
            var fixed = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); fixed.id = 2;
            var facts = source(List.of(variable.terms(), fixed.terms()), List.of(), 500_000, Map.of(), Map.of()).household();
            var probe = new RiskRateStress(context.getBeanProvider(RepaymentEngine.class)).probe(facts);
            assertThat(probe.basicBurdenDelta(BigDecimal.ONE)).isEqualTo(10_000);
            assertThat(probe.available(BigDecimal.ONE)).isEqualTo(490_000);
            assertThat(facts.loans()).allSatisfy(loan -> assertThat(loan.annualRate()).isEqualByComparingTo("6"));
        }
    }

    @Test void singleCandidateDeductsActualCashOnceAndManualZeroOnlyAffectsTheCurrentMonth() {
        var loan = new LoanFixture();
        var source = source(List.of(loan.terms()), List.of(plan(1, 100_000, 0, 0)), 500_000, Map.of(1L, 100_000L), Map.of());
        var engine = new DefaultRepaymentEngine();
        var candidate = StrategyEngineInputs.candidate(source, Code.FAST).orElseThrow();
        var result = engine.simulate(candidate.simulation());
        assertThat(result.rows().getFirst().extraCash()).isEqualTo(300_000);
        assertThat(result.rows().get(1).extraCash()).isEqualTo(400_000);
        assertThat(result.totalInterest()).isLessThan(engine.simulate(StrategyEngineInputs.baseline(source)).totalInterest());
        var overridden = StrategyEngineInputs.candidate(source, Code.FAST,
            List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, 0, null))).orElseThrow();
        result = engine.simulate(overridden.simulation());
        assertThat(result.rows().getFirst().extraCash()).isZero();
        assertThat(result.rows().get(1).extraCash()).isEqualTo(400_000);
    }

    @Test void realPriorityBuilderAndEngineRetainTheBudgetAfterTheFirstTargetPaysOff() {
        var a = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); a.balance = 100; a.rate = BigDecimal.ZERO; a.fixedPrincipal = 50L;
        var b = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); b.id = 2; b.balance = 1_000; b.rate = BigDecimal.ZERO; b.fixedPrincipal = 100L;
        var source = source(List.of(a.terms(), b.terms()), List.of(plan(1, 200, 0, 0), plan(2, 0, 0, 0)), 1_000, Map.of(), Map.of());
        var candidate = StrategyEngineInputs.candidate(source, Code.INTEREST_SAVING).orElseThrow();
        var result = new DefaultRepaymentEngine().simulate(candidate.simulation());
        assertThat(result.rows().get(0).extraCash()).isEqualTo(50);
        assertThat(result.rows().get(1).extraCash()).isEqualTo(150);
        assertThat(result.rows().get(2).extraCash()).isEqualTo(200);
        assertThat(result.rows().get(2).loanId()).isEqualTo(2);
    }

    @Test void realProjectionAdapterDistinguishesFundedAndUnfundedBulletMaturity() {
        try (var context = new AnnotationConfigApplicationContext(RepaymentEngineConfiguration.class)) {
            var loan = new LoanFixture(RepaymentMethod.BULLET);
            var source = source(List.of(loan.terms()), List.of(plan(1, 0, 1_000_000, 1_000_000)), 2_000_000, Map.of(), Map.of());
            var projection = new RiskPayoffProjection(context.getBeanProvider(RepaymentEngine.class));
            var result = projection.project(source.household(), StrategyEngineInputs.baseline(source).monthlyAmounts(), Map.of());
            assertThat(result.feasibility()).isEqualTo("HIGH");
            assertThat(result.projectedPayoffDate()).isEqualTo(loan.maturity);
            result = projection.project(source.household(), Map.of(), Map.of());
            assertThat(result.feasibility()).isEqualTo("LOW");
            assertThat(result.projectedPayoffDate()).isNull();
            assertThat(result.warnings()).contains("BULLET_SAVINGS_SHORTFALL");
        }
    }
}
