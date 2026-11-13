-- DevMonitor 5.0 - capture every Gateway health-state transition consistently.
BEGIN;

CREATE OR REPLACE FUNCTION dm_capture_gateway_health_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.health_state IS DISTINCT FROM NEW.health_state THEN
        INSERT INTO dm_gateway_health_events(
            gateway_id,previous_state,new_state,app_version,event_at
        ) VALUES (
            NEW.id,OLD.health_state,NEW.health_state,NEW.app_version,now()
        );
        INSERT INTO dm_gateway_audit_events(
            gateway_id,event_type,details
        ) VALUES (
            NEW.id,'GATEWAY_HEALTH_CHANGED',
            jsonb_build_object('previous_state',OLD.health_state,'new_state',NEW.health_state)
        );
        PERFORM dm_rebalance_unstarted_batches();
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS dm_gateway_health_transition ON dm_gateways;
CREATE TRIGGER dm_gateway_health_transition
AFTER UPDATE OF health_state ON dm_gateways
FOR EACH ROW EXECUTE FUNCTION dm_capture_gateway_health_transition();

COMMIT;
