package com.loanrun.loanproduct.service;

import com.loanrun.loanproduct.client.FssCatalogClient.Snapshot;
import java.time.Clock;
import java.util.Locale;
import com.loanrun.loanproduct.repository.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogWriter {
    private final CatalogRepository repository;private final Clock clock;
    public CatalogWriter(CatalogRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Transactional public void replace(Snapshot snapshot){
        // Calls finish before this transaction. One failure retains the complete last successful catalog.
        for(var p:snapshot.products()){
            if(p.name().contains("마이너스")||p.name().contains("한도대출")||p.name().contains("종합통장")||p.name().toLowerCase(Locale.ROOT).contains("overdraft"))
                throw new IllegalArgumentException("Unsupported credit-line product");
        }
        repository.replace(snapshot,clock.instant());
    }
}
