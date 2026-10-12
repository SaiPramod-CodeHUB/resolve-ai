package com.saipramod.resolveai.domain;

import com.saipramod.resolveai.domain.Domain.CarrierStatus;
import com.saipramod.resolveai.domain.Domain.Category;
import com.saipramod.resolveai.domain.Domain.Customer;
import com.saipramod.resolveai.domain.Domain.Order;

import java.time.LocalDate;

/** A handful of hand-written customers and orders for the demo UI and API examples. */
public final class SeedData {

    public static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    private SeedData() { }

    public static DataStore demo() {
        DataStore s = new DataStore(TODAY);
        s.put(new Customer("C-100", "Maya Chen", 820, 0, 0));
        s.put(new Customer("C-200", "Arjun Rao", 400, 1, 0));
        s.put(new Customer("C-300", "Sam Ortiz", 6, 4, 1));          // new account, many refunds, a chargeback
        s.put(new Customer("C-400", "Priya Nair", 1500, 0, 0));

        s.put(new Order("ORD-1001", "C-100", "Ceramic pour-over set", 48.00, Category.PHYSICAL,
                TODAY.minusDays(20), TODAY.minusDays(14), null, CarrierStatus.LOST, 1, false));
        s.put(new Order("ORD-1002", "C-100", "Noise-cancelling headphones", 329.00, Category.PHYSICAL,
                TODAY.minusDays(12), TODAY.minusDays(7), TODAY.minusDays(6), CarrierStatus.DELIVERED, 1, false));
        s.put(new Order("ORD-1003", "C-200", "Linen shirt (final sale)", 39.00, Category.FINAL_SALE,
                TODAY.minusDays(10), TODAY.minusDays(5), TODAY.minusDays(5), CarrierStatus.DELIVERED, 1, false));
        s.put(new Order("ORD-1004", "C-200", "Desk lamp", 64.00, Category.PHYSICAL,
                TODAY.minusDays(9), TODAY.minusDays(4), TODAY.minusDays(3), CarrierStatus.DELIVERED, 2, false));
        s.put(new Order("ORD-1005", "C-300", "Smartwatch", 249.00, Category.PHYSICAL,
                TODAY.minusDays(8), TODAY.minusDays(3), TODAY.minusDays(3), CarrierStatus.DELIVERED, 1, false));
        s.put(new Order("ORD-1006", "C-400", "Photo editing course", 79.00, Category.DIGITAL,
                TODAY.minusDays(15), TODAY.minusDays(15), TODAY.minusDays(15), CarrierStatus.DELIVERED, 1, true));
        s.put(new Order("ORD-1007", "C-400", "Hiking backpack", 120.00, Category.PHYSICAL,
                TODAY.minusDays(60), TODAY.minusDays(54), TODAY.minusDays(53), CarrierStatus.DELIVERED, 1, false));
        return s;
    }
}
