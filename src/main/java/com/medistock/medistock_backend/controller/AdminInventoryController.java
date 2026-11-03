package com.medistock.medistock_backend.controller;

import com.medistock.medistock_backend.dto.MedicineRequest;
import com.medistock.medistock_backend.dto.MedicineResponse;
import com.medistock.medistock_backend.entity.Medicine;
import com.medistock.medistock_backend.entity.StockLog;
import com.medistock.medistock_backend.entity.Supplier;
import com.medistock.medistock_backend.repository.MedicineRepository;
import com.medistock.medistock_backend.repository.StockLogRepository;
import com.medistock.medistock_backend.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ADMIN', 'ROLE_PHARMACIST', 'PHARMACIST', 'ROLE_STAFF', 'STAFF' )")
public class AdminInventoryController {

    private final MedicineRepository medicineRepository;
    private final SupplierRepository supplierRepository;
    private final StockLogRepository stockLogRepository;

    // --- MEDICINES CRUD ---

    @GetMapping("/medicines")
    public ResponseEntity<List<MedicineResponse>> getAllMedicines() {
        List<MedicineResponse> list = medicineRepository.findAll().stream()
                .map(MedicineResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @PostMapping("/medicines")
    public ResponseEntity<MedicineResponse> addMedicine(@RequestBody MedicineRequest request) {
        if (medicineRepository.existsByBatchNumber(request.getBatchNumber())) {
            throw new RuntimeException("Medicine with this batch number already exists");
        }

        Medicine medicine = new Medicine();
        medicine.setName(request.getName());
        medicine.setBatchNumber(request.getBatchNumber());
        medicine.setCategory(request.getCategory());
        medicine.setQuantity(request.getQuantity());
        medicine.setMinQuantity(request.getMinQuantity());
        medicine.setManufacturingDate(request.getManufacturingDate());
        medicine.setExpiryDate(request.getExpiryDate());
        medicine.setUnitPrice(request.getUnitPrice());

        if (request.getSupplierId() != null) {
            Supplier supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new RuntimeException("Supplier not found"));
            medicine.setSupplier(supplier);
        }

        Medicine saved = medicineRepository.save(medicine);

        if (saved.getQuantity() > 0) {
            StockLog log = new StockLog(saved, saved.getQuantity(), "New Stock Added");
            stockLogRepository.save(log);
        }

        return ResponseEntity.ok(MedicineResponse.fromEntity(saved));
    }

    @PutMapping("/medicines/{id}")
    public ResponseEntity<MedicineResponse> updateMedicine(@PathVariable Long id, @RequestBody MedicineRequest request) {
        Medicine medicine = medicineRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Medicine not found"));

        int oldQuantity = medicine.getQuantity();
        int newQuantity = request.getQuantity();

        medicine.setName(request.getName());
        medicine.setBatchNumber(request.getBatchNumber());
        medicine.setCategory(request.getCategory());
        medicine.setQuantity(newQuantity);
        medicine.setMinQuantity(request.getMinQuantity());
        medicine.setManufacturingDate(request.getManufacturingDate());
        medicine.setExpiryDate(request.getExpiryDate());
        medicine.setUnitPrice(request.getUnitPrice());
        medicine.setUpdatedAt(LocalDateTime.now());

        if (request.getSupplierId() != null) {
            Supplier supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new RuntimeException("Supplier not found"));
            medicine.setSupplier(supplier);
        }

        Medicine saved = medicineRepository.save(medicine);

        int diff = newQuantity - oldQuantity;
        if (diff != 0) {
            String reason = diff > 0 ? "Inventory Restock" : "Inventory Adjustment";
            StockLog log = new StockLog(saved, diff, reason);
            stockLogRepository.save(log);
        }

        return ResponseEntity.ok(MedicineResponse.fromEntity(saved));
    }

    @DeleteMapping("/medicines/{id}")
    public ResponseEntity<?> deleteMedicine(@PathVariable Long id) {
        try {
            if (!medicineRepository.existsById(id)) {
                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Medicine not found"));
            }
            medicineRepository.deleteById(id);
            return ResponseEntity.ok(java.util.Map.of("message", "Medicine deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Map.of("message", "Cannot delete medicine because it is linked to a Purchase Order."));
        }
    }

    // --- INVENTORY LOGS ---

    @GetMapping("/stock-logs")
    public ResponseEntity<?> getStockLogs() {
        return ResponseEntity.ok(stockLogRepository.findAllByOrderByTimestampDesc());
    }

    // --- DASHBOARD STATS ---

    @GetMapping("/dashboard-stats")
    public ResponseEntity<?> getDashboardStats() {
        try {
            List<Medicine> allMeds = medicineRepository.findAll();
            LocalDate today = LocalDate.now();

            long inStock = 0;
            long lowStock = 0;
            long outOfStock = 0;
            long expired = 0;

            for (Medicine m : allMeds) {
                if (m.getExpiryDate() != null && m.getExpiryDate().isBefore(today)) {
                    expired++;
                } else if (m.getQuantity() == 0) {
                    outOfStock++;
                } else if (m.getQuantity() <= m.getMinQuantity()) {
                    lowStock++;
                } else {
                    inStock++;
                }
            }

            double totalValue = allMeds.stream()
                    .mapToDouble(m -> m.getQuantity() * (m.getUnitPrice() != null ? m.getUnitPrice() : 0.0))
                    .sum();

            return ResponseEntity.ok(Map.of(
                    "totalMedicines", allMeds.size(),
                    "inStockCount", inStock,
                    "lowStockCount", lowStock,
                    "outOfStockCount", outOfStock,
                    "expiredCount", expired,
                    "totalInventoryValue", Math.round(totalValue * 100.0) / 100.0
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // --- EXPIRY TRACKING ---

    @GetMapping("/expiring")
    public ResponseEntity<List<MedicineResponse>> getExpiringMedicines() {
        LocalDate alertDate = LocalDate.now().plusDays(60);

        List<MedicineResponse> expiring = medicineRepository.findAll().stream()
                .filter(m -> m.getExpiryDate() != null && m.getExpiryDate().isBefore(alertDate))
                .map(MedicineResponse::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(expiring);
    }

    // --- DASHBOARD CHART DATA ---

    @GetMapping("/dashboard-categories")
    public ResponseEntity<?> getCategoryStats() {
        try {
            List<Medicine> allMeds = medicineRepository.findAll();

            Map<String, Long> categoryCounts = allMeds.stream()
                    .filter(m -> m.getCategory() != null && !m.getCategory().trim().isEmpty())
                    .collect(Collectors.groupingBy(Medicine::getCategory, Collectors.counting()));

            List<Map<String, Object>> chartData = categoryCounts.entrySet().stream()
                    .map(entry -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", entry.getKey());
                        map.put("count", entry.getValue());
                        return map;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(chartData);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate category stats: " + e.getMessage()));
        }
    }

    // --- CATEGORIES ---
    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, Object>>> getAllCategories() {
        List<Medicine> allMeds = medicineRepository.findAll();

        // Group medicines by category string
        Map<String, List<Medicine>> grouped = allMeds.stream()
                .filter(m -> m.getCategory() != null && !m.getCategory().trim().isEmpty())
                .collect(Collectors.groupingBy(Medicine::getCategory));

        List<Map<String, Object>> response = new ArrayList<>();
        int idCounter = 1;

        for (Map.Entry<String, List<Medicine>> entry : grouped.entrySet()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", idCounter++);
            map.put("name", entry.getKey());
            map.put("description", entry.getValue().size() + " medicines assigned to this category.");
            response.add(map);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/categories")
    public ResponseEntity<?> addCategory(@RequestBody Map<String, String> request) {
        return ResponseEntity.ok(Map.of("message", "Category saved successfully"));
    }

    @PutMapping("/categories/{oldName}")
    public ResponseEntity<String> updateCategory(@PathVariable String oldName, @RequestParam String newName) {
        List<Medicine> medicines = medicineRepository.findAll().stream()
                .filter(m -> oldName.equals(m.getCategory()))
                .collect(Collectors.toList());

        for (Medicine m : medicines) {
            m.setCategory(newName);
            medicineRepository.save(m);
        }
        return ResponseEntity.ok("Category updated across " + medicines.size() + " medicines");
    }

    @DeleteMapping("/categories/{name}")
    public ResponseEntity<String> deleteCategory(@PathVariable String name) {
        List<Medicine> medicines = medicineRepository.findAll().stream()
                .filter(m -> name.equals(m.getCategory()))
                .collect(Collectors.toList());

        for (Medicine m : medicines) {
            m.setCategory("Uncategorized");
            medicineRepository.save(m);
        }
        return ResponseEntity.ok("Category deleted and medicines moved to Uncategorized");
    }
}