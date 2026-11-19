import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class MigrationAuditTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @TempDir Path temp;
    static final Map<Integer,String> BLOBS=Map.of(
        1,"e989bbbb2e6aa9f82d76596d4d0b0c7ef8dea945",2,"1419c7b71981d27bdf225af0d4eb54c46d5ca5d9",
        3,"bddc7315a0cfc86d362c652d6744d60f2fda8241",5,"7037d1a3736f0393b4e48cce15c9fbdf8dd96483");
    String blob(Path path)throws Exception {
        byte[] bytes=Files.readAllBytes(path);var hash=java.security.MessageDigest.getInstance("SHA-1");
        hash.update(("blob "+bytes.length+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash.digest(bytes));
    }
    JdbcTemplate jdbc(){return new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));}
    Path files(int... versions)throws Exception {
        var dir=Files.createTempDirectory(temp,"versions-");
        try(var paths=Files.list(Path.of(System.getProperty("auditRoot"),"assets"))){
            for(var p:paths.toList())for(int v:versions)if(p.getFileName().toString().startsWith("V"+v+"__")) {
                assertThat(blob(p)).as(p.getFileName().toString()).isEqualTo(BLOBS.get(v));
                Files.copy(p,dir.resolve(p.getFileName()));
            }
        }
        assertThat(Files.list(dir).count()).isEqualTo(versions.length);return dir;
    }
    Flyway flyway(String schema,Path files,boolean outOfOrder){return Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword())
        .schemas(schema).defaultSchema(schema).locations("filesystem:"+files).outOfOrder(outOfOrder).load();}
    List<Map<String,Object>> history(String schema){return jdbc().queryForList("SELECT version,checksum,script,installed_rank,success FROM "+schema+".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");}
    Map<String,List<String>> business(String schema){
        var out=new TreeMap<String,List<String>>();
        for(var name:jdbc().queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class,schema))
            out.put(name,jdbc().queryForList("SELECT to_jsonb(t)::text FROM "+schema+"."+name+" t ORDER BY to_jsonb(t)::text",String.class));
        return out;
    }
    void seed(String schema,boolean v3,boolean v5){
        var j=jdbc();
        j.update("INSERT INTO "+schema+".demo_users(session_digest,name,created_at,updated_at) VALUES ('audit-user','가상사용자','2026-11-01T00:00:00Z','2026-11-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".notification_settings(user_id,payment_due) VALUES (1,false)");
        if(v3){
            j.update("INSERT INTO "+schema+".finance_profiles(user_id,monthly_income,income_type,payday,fixed_essential_expenses,variable_essential_expenses,discretionary_expenses,savings,created_at,updated_at) VALUES (1,3000000,'REGULAR',25,0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            j.update("INSERT INTO "+schema+".monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,created_at,updated_at) VALUES (1,'2026-11-01','REGULAR',3000000,25,0,0,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            j.update("INSERT INTO "+schema+".monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (1,999999,'EXTRA_PAYMENT',123)");
        }
        if(v5){
            j.update("INSERT INTO "+schema+".comparison_settings(user_id,consented) VALUES (1,false)");
            j.update("INSERT INTO "+schema+".report_jobs(user_id,loan_id,scope_key,data_revision,basis_month,status,input_payload,created_at,updated_at) VALUES (1,999999,'LOAN:999999',0,'2026-11-01','PENDING','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
    }
    List<String> shape(String schema){
        var rows=new ArrayList<String>();
        rows.addAll(jdbc().queryForList("SELECT table_name||'|'||column_name||'|'||udt_name||'|'||is_nullable||'|'||is_identity||'|'||COALESCE(column_default,'') FROM information_schema.columns WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position",String.class,schema));
        rows.addAll(jdbc().queryForList("SELECT t.relname||'|'||c.conname||'|'||pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname<>'flyway_schema_history' ORDER BY t.relname,c.conname",String.class,schema));
        rows.addAll(jdbc().queryForList("SELECT tablename||'|'||indexname||'|'||indexdef FROM pg_indexes WHERE schemaname=? AND tablename<>'flyway_schema_history' ORDER BY tablename,indexname",String.class,schema));
        return rows.stream().map(r->r.replace(schema+".","SCHEMA.")).toList();
    }
    void inspect(String schema)throws Exception {
        db.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(Path.of(System.getProperty("auditInspect"))),"/tmp/inspect.sql");
        var result=db.execInContainer("psql","-U",db.getUsername(),"-d",db.getDatabaseName(),"-v","audit_schema="+schema,"-f","/tmp/inspect.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStdout()).contains("COMMIT");
        System.out.println("AUDIT read-only psql inspection: PASS for "+schema);
    }
    @Test void freshV1V2V3V5IsOrderedAndRepeatableWithDefaultSettings()throws Exception {
        var dir=files(1,2,3,5);var f=flyway("fresh",dir,false);
        assertThat(f.migrate().migrationsExecuted).isEqualTo(4);f.validate();assertThat(f.migrate().migrationsExecuted).isZero();
        assertThat(history("fresh").stream().map(r->r.get("version")).toList()).containsExactly("1","2","3","5");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='fresh' AND table_name='loans'",Long.class)).isEqualTo(1);
        assertThat(f.info().all()).noneSatisfy(i->assertThat(i.getState()).isEqualTo(MigrationState.OUT_OF_ORDER));
        System.out.println("AUDIT fresh-history: "+history("fresh"));
        System.out.println("AUDIT fresh default-order: PASS; V4 absent, not fabricated; engine="+Flyway.class.getProtectionDomain().getCodeSource().getLocation());
    }
    @ParameterizedTest @ValueSource(strings={"13","15","135"})
    void legacyDefaultBlocksV2AndOneOffOutOfOrderPreservesExistingRowsAndChecksums(String scenario)throws Exception {
        boolean v3=scenario.contains("3"),v5=scenario.contains("5");String schema="legacy"+scenario;
        int[] versions=scenario.chars().map(c->c-'0').toArray();flyway(schema,files(versions),false).migrate();seed(schema,v3,v5);
        var before=business(schema);var oldHistory=history(schema);
        // Limit available higher files to the already applied versions plus V2 so only V2 should run.
        var target=files(IntStream.concat(IntStream.of(2),IntStream.of(versions)).distinct().toArray());
        assertThatThrownBy(()->flyway(schema,target,false).migrate()).isInstanceOf(FlywayException.class);
        assertThat(business(schema)).isEqualTo(before);assertThat(history(schema)).isEqualTo(oldHistory);
        var once=flyway(schema,target,true);assertThat(once.migrate().migrationsExecuted).isEqualTo(1);once.validate();
        assertThat(Arrays.stream(once.info().all()).filter(i->i.getVersion()!=null&&i.getVersion().toString().equals("2")).findFirst().orElseThrow().getState()).isEqualTo(MigrationState.OUT_OF_ORDER);
        var after=business(schema);before.forEach((table,rows)->assertThat(after.get(table)).as(schema+" "+table).isEqualTo(rows));
        assertThat(history(schema).stream().filter(r->!r.get("version").equals("2")).toList()).isEqualTo(oldHistory);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM "+schema+".loans",Long.class)).isZero();
        var normal=flyway(schema,target,false);normal.validate();assertThat(normal.migrate().migrationsExecuted).isZero();
        if(v3)assertThat(jdbc().queryForObject("SELECT count(*) FROM "+schema+".monthly_allocations a JOIN "+schema+".monthly_budgets b ON b.id=a.budget_id LEFT JOIN "+schema+".loans l ON l.id=a.loan_id AND l.user_id=b.user_id WHERE l.id IS NULL",Long.class)).isEqualTo(1);
        if(v5)assertThat(jdbc().queryForObject("SELECT count(*) FROM "+schema+".report_jobs r LEFT JOIN "+schema+".loans l ON l.id=r.loan_id AND l.user_id=r.user_id WHERE r.loan_id IS NOT NULL AND l.id IS NULL",Long.class)).isEqualTo(1);
        var ordered=flyway(schema+"_ordered",target,false);ordered.migrate();
        assertThat(shape(schema)).isEqualTo(shape(schema+"_ordered"));
        inspect(schema);
        System.out.println("AUDIT "+schema+": schema equals ordered fresh install; default BLOCKED; one-off V2 PASS; old rows/history unchanged; normal restart PASS; orphan references retained/detected");
    }
    @Test void incompleteOrChangedHistoricalMigrationBundleMustNotBeRepairedAway()throws Exception {
        String schema="tamper";var all=files(1,2,3,5);flyway(schema,all,false).migrate();
        assertThatThrownBy(()->flyway(schema,files(1,2,5),false).validate()).isInstanceOf(FlywayException.class);
        var v3=Files.list(all).filter(p->p.getFileName().toString().startsWith("V3__")).findFirst().orElseThrow();
        Files.writeString(v3,"\n-- checksum drift\n",StandardOpenOption.APPEND);
        assertThatThrownBy(()->flyway(schema,all,false).validate()).isInstanceOf(FlywayException.class);
        inspect("missing_schema");
        System.out.println("AUDIT missing-middle-script/checksum mismatch: blocked as expected; no repair/history deletion");
    }
}
