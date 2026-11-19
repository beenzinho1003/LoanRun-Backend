package com.loanrun.payment.service;

import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.error.ApiException;
import com.loanrun.payment.domain.DueStateCalculator;
import com.loanrun.payment.read.*;
import com.loanrun.payment.repository.StoredDueRepository;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StoredDueQueryService {
    private final StoredDueRepository dues;
    private final Clock clock;
    public StoredDueQueryService(StoredDueRepository dues,Clock clock) { this.dues=dues;this.clock=clock; }
    public StoredDueProjection find(long userId,long dueId) {
        var today=LocalDate.now(clock);return project(dues.find(userId,dueId),today);
    }
    public List<StoredDueProjection> range(long userId,DateRange range,Long loanId) {
        var today=LocalDate.now(clock);return dues.range(userId,range,loanId).stream().map(source->project(source,today)).toList();
    }
    private static StoredDueProjection project(StoredDueSource source,LocalDate today) {
        if(source.nonRegularAllocations()>0)throw ApiException.unavailable(); // Never count EXTRA as regular fulfillment.
        try {
            var due=source.due();
            return new StoredDueProjection(source,today,DueStateCalculator.calculate(due.requiredAmount(),source.principal(),source.interest(),
                source.fee(),source.allocationCount(),due.confirmedUnpaid(),due.dueDate(),today));
        } catch(ArithmeticException|IllegalArgumentException ex){throw ApiException.unavailable();}
    }
}
