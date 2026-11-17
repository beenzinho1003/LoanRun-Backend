package com.loanrun.loanproduct.service;

import com.loanrun.loanproduct.client.FssCatalogClient;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CatalogRefreshService {
    private static final Logger log=LoggerFactory.getLogger(CatalogRefreshService.class);
    private final FssCatalogClient client;private final CatalogWriter writer;private final boolean enabled;
    public CatalogRefreshService(FssCatalogClient client,CatalogWriter writer,@Value("${FSS_ENABLED:false}")boolean enabled){this.client=client;this.writer=writer;this.enabled=enabled;}
    @Scheduled(cron="0 0 3 * * *",zone="Asia/Seoul")public void refresh(){
        if(!enabled)return;
        try{writer.replace(client.fetch());}catch(RuntimeException ex){log.warn("Catalog refresh failed; retaining prior successful cache ({})",ex.getClass().getSimpleName());}
    }
}
