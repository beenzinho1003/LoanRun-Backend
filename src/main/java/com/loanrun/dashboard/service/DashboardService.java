package com.loanrun.dashboard.service;

import com.loanrun.common.contract.DashboardQueryPort.DueItem;
import com.loanrun.common.contract.PlanType;
import com.loanrun.dashboard.dto.DashboardDtos.*;
import com.loanrun.risk.service.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final DashboardInputService inputs;private final RiskRateStress rates;private final Clock clock;
    public DashboardService(DashboardInputService inputs,RiskRateStress rates,Clock clock){this.inputs=inputs;this.rates=rates;this.clock=clock;}
    public Detail read(long userId,Long selected){
        var captured=inputs.capture(userId,selected);var ledger=captured.ledger();
        Risk risk=new Risk(null,null,true,null);
        if(captured.plan()==PlanType.PRO){
            var facts=captured.risk();
            long basic=Math.subtractExact(Math.addExact(facts.due().scheduledCash(),facts.due().previousOutstandingCash()),facts.due().savingsUsedForDue());
            var probe=rates.probe(facts);
            var assessment=RiskMath.assess(facts.finance().planningIncome(),facts.capacity().availableCash(),basic,
                facts.finance().actualDiscretionaryExpenses(),facts.finance().discretionaryLimit(),facts.variableExposurePct(),
                probe.hasVariableLoans(),probe.available(BigDecimal.ONE),facts.monthlyTarget(),!facts.loans().isEmpty());
            risk=new Risk(assessment.level(),facts.capacity().availableCash(),false,assessment.score());
        }
        var summary=ledger.monthlySummary();
        var reportStatus=DashboardReportStatusPolicy.read(userId,captured.plan(),captured.report(),captured.revision(),clock);
        if(captured.plan()==PlanType.PRO&&reportStatus.updatedAt()==null&&captured.lastCompleted()!=null){
            var completed=captured.lastCompleted();
            if(completed.userId()!=userId)throw com.loanrun.common.error.ApiException.unavailable();
            reportStatus=new DashboardReportStatusPolicy.Status(reportStatus.status(),reportStatus.reportId(),reportStatus.stale(),false,
                completed.generatedAt()==null?null:completed.generatedAt().atZone(clock.getZone()).toOffsetDateTime(),false);
        }
        return new Detail(captured.plan(),selected,ledger.loans().stream().map(loan->new Loan(loan.loanId(),loan.productName(),loan.remainingPrincipal(),
            loan.paidPrincipal(),loan.progressPercent(),loan.savingsBalance(),loan.status(),loan.requiredMonthlySaving())).toList(),
            ledger.totalRemainingPrincipal(),ledger.overallProgressPercent(),ledger.estimatedPayoffDate(),payment(ledger.nextPayment()),
            new Monthly(summary.scheduledRepayment(),summary.actualRepayment(),summary.remainingRequiredAmount(),summary.plannedExtraAmount(),summary.actualExtraAmount(),
                summary.completedCount(),summary.totalCount(),summary.remainingAmount(),summary.plannedSavingAmount(),summary.actualSavingAmount()),risk,
            reportStatus,
            ledger.monthlyChecklist().stream().sorted(Comparator.comparing(DueItem::dueDate).thenComparingLong(DueItem::dueItemId)).map(DashboardService::payment).toList());
    }
    private static Payment payment(DueItem due){return due==null?null:new Payment(due.dueItemId(),due.loanId(),due.dueDate(),due.requiredAmount(),due.plannedExtraAmount(),
        due.paidRequiredAmount(),due.paidExtraAmount(),due.outstandingAmount(),due.status(),due.productName(),due.totalPlannedAmount(),due.displayStatus(),
        due.remainingExtraAmount(),due.unpaidConfirmed(),due.plannedSavingAmount(),due.actualSavingAmount());}
}
