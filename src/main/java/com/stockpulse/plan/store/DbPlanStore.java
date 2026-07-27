package com.stockpulse.plan.store;

import com.stockpulse.plan.PlanStore;
import com.stockpulse.plan.TradingPlan;
import com.stockpulse.plan.TradingPlanEntity;
import com.stockpulse.plan.TradingPlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the plan JSON to the database via {@link TradingPlanRepository}. Upserts on the
 * {@code plan_date} natural key so a re-run for the same date replaces the prior row.
 */
@Slf4j
@Component
public class DbPlanStore implements PlanStore {

    private final TradingPlanRepository repository;

    public DbPlanStore(TradingPlanRepository repository) {
        this.repository = repository;
    }

    @Override
    public String storeName() {
        return "db";
    }

    @Override
    @Transactional
    public void save(TradingPlan plan, String json) {
        TradingPlanEntity entity = repository.findByPlanDate(plan.getPlanDate())
                .orElseGet(() -> TradingPlanEntity.builder().planDate(plan.getPlanDate()).build());

        entity.setSchemaVersion(plan.getSchemaVersion());
        entity.setContent(json);
        entity.setGeneratedAt(plan.getGeneratedAt());

        TradingPlanEntity saved = repository.save(entity);
        log.info("[plan:db] saved plan id={} for {}", saved.getId(), saved.getPlanDate());
    }
}
