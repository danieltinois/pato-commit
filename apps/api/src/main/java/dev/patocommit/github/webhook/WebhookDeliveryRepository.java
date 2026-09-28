package dev.patocommit.github.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code webhook_delivery}.
 *
 * <p>Plain {@link JdbcClient} rather than JPA. Every operation this table needs
 * is a single statement whose safety comes from the database itself: the claim is
 * a conditional {@code UPDATE ... RETURNING}, and the dedup is
 * {@code ON CONFLICT DO NOTHING}. An ORM would add an entity manager and a
 * version column around a table that has no relationships and one state machine.
 */
@Repository
class WebhookDeliveryRepository {

	private static final String COLUMNS = """
			delivery_id, event_type, action, repository_id, payload, status,
			attempts, error, received_at, processing_started_at, processed_at
			""";

	/**
	 * Same columns, qualified. {@code claimStale} updates from a CTE, so
	 * {@code delivery_id} exists on both sides and must be disambiguated.
	 */
	private static final String COLUMNS_QUALIFIED = """
			d.delivery_id, d.event_type, d.action, d.repository_id, d.payload, d.status,
			d.attempts, d.error, d.received_at, d.processing_started_at, d.processed_at
			""";

	private final JdbcClient jdbc;

	WebhookDeliveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the delivery. Returns {@code false} when the id is already known,
	 * which is how a redelivery is recognised: it collides with the existing row
	 * instead of creating a second one.
	 */
	boolean insert(WebhookDelivery delivery) {
		int inserted = jdbc.sql("""
						INSERT INTO webhook_delivery
						    (delivery_id, event_type, action, repository_id, payload, status, attempts, received_at)
						VALUES (:id, :event, :action, :repositoryId, CAST(:payload AS jsonb), 'PENDING', 0, :receivedAt)
						ON CONFLICT (delivery_id) DO NOTHING
						""")
				.param("id", delivery.deliveryId())
				.param("event", delivery.eventType())
				.param("action", delivery.action())
				.param("repositoryId", delivery.repositoryId())
				.param("payload", delivery.payload())
				.param("receivedAt", OffsetDateTime.ofInstant(delivery.receivedAt(), ZoneOffset.UTC))
				.update();
		return inserted == 1;
	}

	/**
	 * Claims a delivery for processing: {@code PENDING -> PROCESSING}, incrementing
	 * {@code attempts} and stamping {@code processing_started_at}.
	 *
	 * <p>The {@code AND status = 'PENDING'} predicate is the whole mechanism. Two
	 * callers racing on the same id serialise on the row lock, and only the one
	 * whose update still matches a {@code PENDING} row gets a row back. A caller
	 * that loses the race, or that arrives after the delivery reached a terminal
	 * state, gets {@link Optional#empty()} and does no work.
	 */
	Optional<WebhookDelivery> claim(UUID deliveryId) {
		return jdbc.sql("""
						UPDATE webhook_delivery
						   SET status = 'PROCESSING',
						       attempts = attempts + 1,
						       processing_started_at = now()
						 WHERE delivery_id = :id
						   AND status = 'PENDING'
						RETURNING
						"""
						+ COLUMNS)
				.param("id", deliveryId)
				.query(DeliveryRowMapper.INSTANCE)
				.optional();
	}

	/**
	 * Claims everything that has been waiting or stuck for longer than
	 * {@code threshold}: {@code PENDING} past its age, or {@code PROCESSING} past
	 * the age of its claim. The second case is the crash path — a process that
	 * died between claiming and completing leaves the row {@code PROCESSING} with
	 * a timestamp, and this takes it again instead of leaving it stuck.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} lets a second sweeper take a different
	 * batch rather than blocking, and keeps a row that is being processed right
	 * now out of reach, as long as it stays within the timeout.
	 */
	List<WebhookDelivery> claimStale(Instant threshold, int batchSize) {
		return jdbc.sql("""
						WITH stale AS (
						    SELECT delivery_id
						      FROM webhook_delivery
						     WHERE (status = 'PENDING'     AND received_at           < :threshold)
						        OR (status = 'PROCESSING' AND processing_started_at < :threshold)
						     ORDER BY received_at
						     LIMIT :batchSize
						     FOR UPDATE SKIP LOCKED
						)
						UPDATE webhook_delivery d
						   SET status = 'PROCESSING',
						       attempts = d.attempts + 1,
						       processing_started_at = now()
						  FROM stale s
						 WHERE d.delivery_id = s.delivery_id
						RETURNING
						"""
						+ COLUMNS_QUALIFIED)
				.param("threshold", OffsetDateTime.ofInstant(threshold, ZoneOffset.UTC))
				.param("batchSize", batchSize)
				.query(DeliveryRowMapper.INSTANCE)
				.list();
	}

	void markProcessed(UUID deliveryId) {
		terminal(deliveryId, "PROCESSED", null);
	}

	/** {@code reason} must be a fixed, bounded string: it is stored, not derived from the payload. */
	void markIgnored(UUID deliveryId, String reason) {
		terminal(deliveryId, "IGNORED", reason);
	}

	void markFailed(UUID deliveryId, String error) {
		terminal(deliveryId, "FAILED", error);
	}

	/**
	 * Hands the delivery back to {@code PENDING} so the sweeper picks it up again.
	 * The caller is responsible for checking the attempt budget first.
	 */
	void releaseForRetry(UUID deliveryId, String error) {
		jdbc.sql("""
						UPDATE webhook_delivery
						   SET status = 'PENDING',
						       processing_started_at = NULL,
						       error = :error
						 WHERE delivery_id = :id
						""")
				.param("id", deliveryId)
				.param("error", error)
				.update();
	}

	Optional<WebhookDelivery> find(UUID deliveryId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery WHERE delivery_id = :id")
				.param("id", deliveryId)
				.query(DeliveryRowMapper.INSTANCE)
				.optional();
	}

	private void terminal(UUID deliveryId, String status, String error) {
		jdbc.sql("""
						UPDATE webhook_delivery
						   SET status = :status,
						       processed_at = now(),
						       processing_started_at = NULL,
						       error = :error
						 WHERE delivery_id = :id
						""")
				.param("id", deliveryId)
				.param("status", status)
				.param("error", error)
				.update();
	}

	private enum DeliveryRowMapper implements org.springframework.jdbc.core.RowMapper<WebhookDelivery> {

		INSTANCE;

		@Override
		public WebhookDelivery mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new WebhookDelivery(
					rs.getObject("delivery_id", UUID.class),
					rs.getString("event_type"),
					rs.getString("action"),
					// getObject with a type returns null for SQL NULL. ResultSet.isNull
					// is not available on this JDK.
					rs.getObject("repository_id", Long.class),
					rs.getString("payload"),
					WebhookDeliveryStatus.valueOf(rs.getString("status")),
					rs.getInt("attempts"),
					rs.getString("error"),
					instant(rs, "received_at"),
					instant(rs, "processing_started_at"),
					instant(rs, "processed_at"));
		}

		private static Instant instant(ResultSet rs, String column) throws SQLException {
			OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
			return value == null ? null : value.toInstant();
		}

	}

}
