package com.example.metadata_service.controller;

import com.example.metadata_service.dto.ChecksumUpdateRequest;
import com.example.metadata_service.dto.FileDownloadResponse;
import com.example.metadata_service.dto.FileUploadRequest;
import com.example.metadata_service.dto.FileUploadResponse;
import com.example.metadata_service.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @PostMapping
    public FileUploadResponse initiateUpload(@RequestBody FileUploadRequest request) {
        return fileService.initiateUpload(request);
    }

    @PostMapping("/{fileId}/complete")
    public void completeUpload(@PathVariable String fileId) {
        fileService.completeUpload(fileId);
    }

    @GetMapping("/{fileId}")
    public FileDownloadResponse downloadFile(@PathVariable String fileId) {
        return fileService.getFileForDownload(fileId);
    }

    @PatchMapping("/{fileId}/chunks/{chunkId}/checksum")
    public void updateChecksum(@PathVariable String fileId,
                            @PathVariable String chunkId,
                            @RequestBody ChecksumUpdateRequest request) {
        fileService.updateChunkChecksum(chunkId, request.getChecksum());
    }
}