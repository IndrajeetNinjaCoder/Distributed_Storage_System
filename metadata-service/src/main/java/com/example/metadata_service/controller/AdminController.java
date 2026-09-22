package com.example.metadata_service.controller;

import com.example.metadata_service.service.RepairService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RepairService repairService;

    @PostMapping("/repair")
    public String triggerRepair() {
        repairService.runRepair();
        return "Repair cycle triggered";
    }
}