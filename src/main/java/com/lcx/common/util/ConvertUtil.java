package com.lcx.common.util;

import com.lcx.common.constant.SheetName;
import com.lcx.common.constant.Zone;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class ConvertUtil {

    public static int parseRoleNum(String role) {
        return switch (role) {
            case SheetName.HOST -> 2;
            case SheetName.JUDGEMENT -> 3;
            case SheetName.SCHOOL -> 4;
            case SheetName.CONTESTANT -> 5;
            default -> 0;
        };
    }

    public static String parseZoneSimStr(String zone) {
        return switch (zone) {
            case Zone.NORTH_WEST -> "NW";
            case Zone.SOUTH_WEST -> "SW";
            case Zone.NORTH_EASE -> "NE";
            case Zone.SOUTH_EAST -> "SE";
            case Zone.CENTRAL -> "C";
            case Zone.EAST -> "E";
            case Zone.NATIONAL -> "N";
            default -> "";
        };
    }

    public static LocalDateTime parseDate(String instantStr) {
        // 将字符串转换为长整型
        long instantLong = Long.parseLong(instantStr);
        // 使用Instant和ZoneId将时间戳转换为ZonedDateTime
        Instant instant = Instant.ofEpochMilli(instantLong);
        // 使用系统默认时区，或者指定其他时区，如ZoneId.of("Asia/Shanghai")
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
        // 从ZonedDateTime中提取LocalDateTime（注意这会丢失时区信息）
        return zonedDateTime.toLocalDateTime();
    }

    public static String parseDateStr(LocalDateTime localDateTime) {
        // 转换为ZonedDateTime
        ZonedDateTime zonedDateTime=localDateTime.atZone(ZoneId.systemDefault());
        return String.valueOf(zonedDateTime.toInstant().toEpochMilli());
    }
}
