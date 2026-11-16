package com.llyytt.path.core.rules

/**
 * 所有可调参数集中在这里。
 *
 * 以后你说「采样太密费电」「停留判定太松」，只改这个文件，不进业务逻辑。
 */
object Tuning {

    object Sampling {
        /** 骑行/开车：≥5 m/s（18 km/h） */
        const val FAST_SPEED_MPS = 5f

        /**
         * 走路与慢速的分界。正常步行约 1.0~1.4 m/s（3.6~5.0 km/h），
         * 取 1.4 作为上界；第 5 步做交通方式分类时直接复用这个数。
         */
        const val WALK_SPEED_MPS = 1.4f

        const val FAST_INTERVAL_MS = 1000L
        const val NORMAL_INTERVAL_MS = 2000L
        const val SLOW_INTERVAL_MS = 5000L
    }

    object Filter {
        /** 精度半径差于此值的点直接丢弃，不入库 */
        const val MAX_ACCURACY_M = 100f

        /** 超过此速度基本是跳点，丢弃 */
        const val MAX_SPEED_MPS = 60f
    }

    object Segment {
        // ---- 速度分档：分段分类 与 地图「按速度着色」共用这一套，不要各写一份 ----
        /** 走路上限（含）。约 8 km/h，覆盖散步到快走 */
        const val WALK_MAX_MPS = 2.2

        /** 骑车上限（含）。约 25 km/h，超过就算车 */
        const val BIKE_MAX_MPS = 7.0

        // ---- 停留 ----
        /** 低于此速度视为静止候选 */
        const val STOP_SPEED_MPS = 0.8

        /** 连续静止超过此时长才记为停留（20 秒，能滤掉等红灯这类短暂停顿） */
        const val STOP_MIN_DURATION_MS = 20_000L

        // ---- 中断 ----
        /** 相邻两点时间差超过此值视为信号中断 */
        const val GAP_SPLIT_MS = 120_000L

        // ---- 段合并 ----
        /** 短于此长度的段并入相邻段，避免切得太碎 */
        const val MIN_SEGMENT_MS = 15_000L

        /** 分段前对速度做中位数平滑的窗口 */
        const val SMOOTH_WINDOW = 5
    }

    object Recording {
        /** 冷启动测量：超过这个时长要提示「正在搜星」 */
        const val FIRST_FIX_SLOW_MS = 20_000L

        /** 通知栏刷新间隔 */
        const val TICK_MS = 1000L

        /** 轨迹点攒够多少条落一次库（每条也落，这里只是留个扩展点） */
        const val BATCH_SIZE = 1
    }

    /**
     * 瞬时速度显示。
     *
     * 只影响「显示」：地图按速度着色用的是原始值，不走这里的平滑。
     */
    object SpeedDisplay {
        /** 中位数窗口：取最近几个有效速度 */
        const val WINDOW_SIZE = 5

        /** 低于这个速度算「可能静止」 */
        const val STATIONARY_SPEED_MPS = 0.5

        /** 连续低于阈值超过这么久，才判定为静止（避免走走停停时闪烁） */
        const val STATIONARY_HOLD_MS = 5_000L
    }
}
