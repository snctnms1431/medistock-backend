package com.medistock.medistock_backend.controller;

import com.medistock.medistock_backend.dto.PurchaseOrderRequest;
import com.medistock.medistock_backend.entity.Medicine;
import com.medistock.medistock_backend.entity.PurchaseOrder;
import com.medistock.medistock_backend.entity.PurchaseOrderItem;
import com.medistock.medistock_backend.entity.Supplier;
import com.medistock.medistock_backend.repository.MedicineRepository;
import com.medistock.medistock_backend.repository.PurchaseOrderRepository;
import com.medistock.medistock_backend.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication; // ✅ CORRECT IMPORT
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/purchase-orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'ROLE_PHARMACIST', 'PHARMACIST', 'ROLE_SUPPLIER', 'SUPPLIER')")
public class PurchaseOrderController {

    private final PurchaseOrderRepository orderRepo;
    private final SupplierRepository supplierRepo;
    private final MedicineRepository medicineRepo;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllOrders() {
        return ResponseEntity.ok(mapOrdersToResponse(orderRepo.findAll()));
    }

    // ONLY PHARMACIST CAN CREATE AN ORDER
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PHARMACIST', 'PHARMACIST')")
    public ResponseEntity<?> createOrder(@RequestBody PurchaseOrderRequest request) {
        try {
            Supplier supplier = supplierRepo.findById(request.getSupplierId())
                    .orElseThrow(() -> new RuntimeException("Supplier not found"));

            PurchaseOrder order = new PurchaseOrder();
            order.setOrderNumber("PO-" + (2026 + new java.util.Random().nextInt(10)) + "-" + (1000 + new java.util.Random().nextInt(9000)));
            order.setSupplier(supplier);
            order.setStatus("PENDING"); // Starts as pending for Admin approval

            List<PurchaseOrderItem> items = new ArrayList<>();
            double total = 0.0;

            for (PurchaseOrderRequest.OrderItemDto itemDto : request.getItems()) {
                Medicine med = medicineRepo.findById(itemDto.getMedicineId())
                        .orElseThrow(() -> new RuntimeException("Medicine not found"));

                PurchaseOrderItem item = new PurchaseOrderItem();
                item.setPurchaseOrder(order);
                item.setMedicine(med);
                item.setQuantity(itemDto.getQuantity());
                item.setUnitPrice(itemDto.getUnitPrice());

                items.add(item);
                total += (itemDto.getQuantity() * itemDto.getUnitPrice());
            }

            order.setItems(items);
            order.setTotalAmount(total);
            orderRepo.save(order);
            return ResponseEntity.ok(Map.of("message", "Order created successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ADMIN AND SUPPLIER CAN UPDATE STATUS
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN', 'ROLE_PHARMACIST', 'PHARMACIST', 'SUPPLIER', 'ROLE_SUPPLIER')")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestParam String status) {
        PurchaseOrder order = orderRepo.findById(id).orElseThrow();
        order.setStatus(status.toUpperCase());
        orderRepo.save(order);
        return ResponseEntity.ok(Map.of("message", "Status updated"));
    }

    // ONLY ADMIN CAN DELETE ORDERS
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'ROLE_ADMIN')")
    public ResponseEntity<?> deleteOrder(@PathVariable Long id) {
        orderRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Order deleted"));
    }

    private List<Map<String, Object>> mapOrdersToResponse(List<PurchaseOrder> orders) {
        return orders.stream().map(order -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", order.getId());
            map.put("orderNumber", order.getOrderNumber());
            map.put("status", order.getStatus());
            map.put("totalAmount", order.getTotalAmount());
            map.put("orderDate", order.getOrderDate());
            map.put("supplierName", order.getSupplier() != null ? order.getSupplier().getName() : "Unknown");

            if (order.getItems() != null) {
                map.put("items", order.getItems().stream().map(item -> {
                    Map<String, Object> itemMap = new HashMap<>();
                    itemMap.put("medicineName", item.getMedicine() != null ? item.getMedicine().getName() : "Unknown");
                    itemMap.put("quantity", item.getQuantity());
                    itemMap.put("unitPrice", item.getUnitPrice());
                    return itemMap;
                }).collect(Collectors.toList()));
            }
            return map;
        }).collect(Collectors.toList());
    }

    // --- SUPPLIER ENDPOINTS ---

    // Get ALL orders for the logged-in supplier
    @GetMapping("/supplier")
    @PreAuthorize("hasAnyAuthority('SUPPLIER', 'ROLE_SUPPLIER')")
    public ResponseEntity<List<Map<String, Object>>> getSupplierOrders(Authentication authentication) {
        List<PurchaseOrder> myOrders = getMyOrders(authentication);
        return ResponseEntity.ok(mapOrdersToResponse(myOrders));
    }

    // Get only active deliveries (APPROVED or PENDING) for the logged-in supplier
    @GetMapping("/supplier/deliveries")
    @PreAuthorize("hasAnyAuthority('SUPPLIER', 'ROLE_SUPPLIER')")
    public ResponseEntity<List<Map<String, Object>>> getSupplierDeliveries(Authentication authentication) {
        List<PurchaseOrder> orders = getMyOrders(authentication);

        List<PurchaseOrder> active = orders.stream()
                .filter(o -> o.getStatus().equals("APPROVED") || o.getStatus().equals("PENDING"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapOrdersToResponse(active));
    }

    // Get order history (RECEIVED or REJECTED) for the logged-in supplier
    @GetMapping("/supplier/history")
    @PreAuthorize("hasAnyAuthority('SUPPLIER', 'ROLE_SUPPLIER')")
    public ResponseEntity<List<Map<String, Object>>> getSupplierHistory(Authentication authentication) {
        List<PurchaseOrder> orders = getMyOrders(authentication);

        List<PurchaseOrder> history = orders.stream()
                .filter(o -> o.getStatus().equals("RECEIVED") || o.getStatus().equals("REJECTED"))
                .collect(Collectors.toList());

        return ResponseEntity.ok(mapOrdersToResponse(history));
    }

    // Helper to fetch all orders belonging to the current supplier
    private List<PurchaseOrder> getMyOrders(Authentication authentication) {
        String email = authentication.getName();
        Supplier supplier = supplierRepo.findAll().stream()
                .filter(s -> s.getEmail() != null && s.getEmail().equalsIgnoreCase(email))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Supplier profile not found"));

        return orderRepo.findAll().stream()
                .filter(o -> o.getSupplier().getId().equals(supplier.getId()))
                .collect(Collectors.toList());
    }

    // STAFF can see approved orders (deliveries in transit)
    @GetMapping("/staff/deliveries")
    @PreAuthorize("hasAnyAuthority('STAFF', 'ROLE_STAFF')")
    public ResponseEntity<List<Map<String, Object>>> getStaffDeliveries() {
        List<PurchaseOrder> approved = orderRepo.findAll().stream()
                .filter(o -> o.getStatus().equals("APPROVED") || o.getStatus().equals("PENDING"))
                .collect(Collectors.toList());
        return ResponseEntity.ok(mapOrdersToResponse(approved));
    }
}