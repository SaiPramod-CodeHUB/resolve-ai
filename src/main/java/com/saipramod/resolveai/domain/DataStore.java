package com.saipramod.resolveai.domain;

import com.saipramod.resolveai.domain.Domain.Customer;
import com.saipramod.resolveai.domain.Domain.Order;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the order system, CRM and payments ledger. "today" is fixed so
 * that policy windows (30-day returns etc.) are deterministic in tests and evals.
 */
public class DataStore {

    public record RefundRecord(String refundId, String orderId, double amount, String reason, String approvedBy) { }

    private final LocalDate today;
    private final Map<String, Customer> customers = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, RefundRecord> refundsByOrder = new ConcurrentHashMap<>();

    public DataStore(LocalDate today) {
        this.today = today;
    }

    public LocalDate today() {
        return today;
    }

    public void put(Customer c) {
        customers.put(c.id(), c);
    }

    public void put(Order o) {
        orders.put(o.id(), o);
    }

    public Optional<Customer> customer(String id) {
        return Optional.ofNullable(id == null ? null : customers.get(id));
    }

    public Optional<Order> order(String id) {
        return Optional.ofNullable(id == null ? null : orders.get(id));
    }

    public List<Order> recentOrders(String customerId, int days) {
        List<Order> out = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.customerId().equals(customerId) && !o.placed().isBefore(today.minusDays(days))) {
                out.add(o);
            }
        }
        out.sort(Comparator.comparing(Order::placed).reversed());
        return out;
    }

    public Optional<RefundRecord> refundFor(String orderId) {
        return Optional.ofNullable(refundsByOrder.get(orderId));
    }

    /** Idempotent: a second refund for the same order returns the first one instead of paying twice. */
    public RefundRecord createRefund(String orderId, double amount, String reason, String approvedBy) {
        return refundsByOrder.computeIfAbsent(orderId, id ->
                new RefundRecord("RF-" + id.replace("ORD-", ""), id, amount, reason, approvedBy));
    }
}
