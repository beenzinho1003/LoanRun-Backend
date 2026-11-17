package com.loanrun;

import static org.mockito.Mockito.*;
import com.loanrun.loanproduct.client.FssCatalogClient;
import com.loanrun.loanproduct.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogRefreshServiceTest {
    @Test void disabledRefreshDoesNotContactClientOrWriter(){
        var client=mock(FssCatalogClient.class);var writer=mock(CatalogWriter.class);
        new CatalogRefreshService(client,writer,false).refresh();verifyNoInteractions(client,writer);
    }
    @Test void failedFetchLeavesTheCacheWriterUntouched(){
        var client=mock(FssCatalogClient.class);var writer=mock(CatalogWriter.class);
        when(client.fetch()).thenThrow(new FssCatalogClient.Unavailable());
        new CatalogRefreshService(client,writer,true).refresh();verify(client).fetch();verifyNoInteractions(writer);
    }
    @Test void onlyACompletedSnapshotIsPassedToTheWriter(){
        var client=mock(FssCatalogClient.class);var writer=mock(CatalogWriter.class);
        var snapshot=new FssCatalogClient.Snapshot(List.of());when(client.fetch()).thenReturn(snapshot);
        new CatalogRefreshService(client,writer,true).refresh();verify(writer).replace(snapshot);
    }
}
