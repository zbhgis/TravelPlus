package com.travelplus.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.TravelNote;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/note")
public class TravelNoteController {

    @Resource
    private ITravelNoteService noteService;

    @PostMapping
    public Result saveTravelNote(@RequestBody TravelNote note) {
        return noteService.saveTravelNote(note);
    }

    @PutMapping("/like/{id}")
    public Result likeTravelNote(@PathVariable("id") Long id) {
        return noteService.likeTravelNote(id);
    }

    @GetMapping("/of/me")
    public Result queryMyTravelNote(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<TravelNote> page = noteService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<TravelNote> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotTravelNote(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return noteService.queryHotTravelNote(current);
    }

    @GetMapping("/{id}")
    public Result queryTravelNoteById(@PathVariable("id") Long id) {
        return noteService.queryTravelNoteById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryTravelNoteLikes(@PathVariable("id") Long id) {
        return noteService.queryTravelNoteLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryTravelNoteByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 根据用户查询
        Page<TravelNote> page = noteService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<TravelNote> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryTravelNoteOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return noteService.queryTravelNoteOfFollow(max, offset);
    }
}
