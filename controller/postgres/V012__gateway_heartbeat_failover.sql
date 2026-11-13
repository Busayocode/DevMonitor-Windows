-- DevMonitor 5.0 - detect lost Gateway heartbeats and fail over queued requests.
BEGIN;

CREATE OR REPLACE FUNCTION dm_mark_stale_gateways_offline()
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE gateway_row RECORD;
        changed_count INTEGER := 0;
BEGIN
    FOR gateway_row IN
        UPDATE dm_gateways
           SET health_state='OFFLINE',updated_at=now()
         WHERE health_state='READY'
           AND revoked_at IS NULL
           AND (heartbeat_at IS NULL OR heartbeat_at < now()-INTERVAL '90 seconds')
        RETURNING id,app_version
    LOOP
        INSERT INTO dm_gateway_health_events(
            gateway_id,previous_state,new_state,app_version
        ) VALUES (
            gateway_row.id,'READY','OFFLINE',gateway_row.app_version
        );
        INSERT INTO dm_gateway_audit_events(
            gateway_id,event_type,details
        ) VALUES (
            gateway_row.id,'GATEWAY_HEARTBEAT_LOST',
            jsonb_build_object('previous_state','READY','new_state','OFFLINE')
        );
        changed_count := changed_count+1;
    END LOOP;

    IF changed_count > 0 THEN
        -- Pending batches are immediately redistributed. Jobs with a live attempt
        -- remain pinned until the lease reaper resolves the attempt safely.
        PERFORM dm_rebalance_unstarted_batches();
    END IF;
    RETURN changed_count;
END;
$$;

COMMIT;
