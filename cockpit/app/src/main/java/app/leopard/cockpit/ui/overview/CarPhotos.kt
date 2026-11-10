package app.leopard.cockpit.ui.overview

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import app.leopard.cockpit.R

/**
 * A garage car's photo, cut out from its background, with its body colour (to tint its
 * row) and whose photo it is.
 */
data class CarPhoto(@DrawableRes val res: Int, val tint: Color, val author: String, val license: String, val source: String)

/** Each garage car's photo: from Wikimedia Commons, cut out, cropped and resized from the original at [CarPhoto.source]. */
object CarPhotos {
    private val all: Map<String, CarPhoto> = mapOf(
        "nissan_gtr" to CarPhoto(R.drawable.car_nissan_gtr, Color(0xFF6B7385), "OSX", "Public domain", "https://commons.wikimedia.org/wiki/File:2009-2010_Nissan_GT-R_(R35)_coupe_01.jpg"),
        "lamborghini_huracan" to CarPhoto(R.drawable.car_lamborghini_huracan, Color(0xFFE6C438), "MrWalkr", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2017_Lamborghini_Huracan_LP610.jpg"),
        "lamborghini_aventador_svj" to CarPhoto(R.drawable.car_lamborghini_aventador_svj, Color(0xFFE6E234), "Calreyn88", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Lamborghini_Aventador_SVJ_2019.jpg"),
        "ferrari_458" to CarPhoto(R.drawable.car_ferrari_458, Color(0xFFE63A25), "Michael Barera", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Ferrari_458_Fort_Worth_June_2016_56_(Ferrari)_(cropped-2).jpg"),
        "porsche_911_gt3" to CarPhoto(R.drawable.car_porsche_911_gt3, Color(0xFFB8BFCC), "Alexander Migl", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Porsche_992_GT3_1X7A0323.jpg"),
        "ford_mustang_gt" to CarPhoto(R.drawable.car_ford_mustang_gt, Color(0xFF2794E6), "Vauxford", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2019_Ford_Mustang_GT_5.0_facelift.jpg"),
        "ford_shelby_gt500" to CarPhoto(R.drawable.car_ford_shelby_gt500, Color(0xFFCEE652), "MrWalkr", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2020_Ford_Mustang_Shelby_GT500_GT101.jpg"),
        "chevrolet_camaro_ss_1969" to CarPhoto(R.drawable.car_chevrolet_camaro_ss_1969, Color(0xFFE8702A), "Ermell", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Chevrolet_Camaro_RS-SS_Convertible_Sachs_Franken_Classic_2018_P5201027.jpg"),
        "chevrolet_camaro_zl1" to CarPhoto(R.drawable.car_chevrolet_camaro_zl1, Color(0xFF22E675), "Xnatedawgx", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2017%E2%80%932018_Chevrolet_Camaro_ZL1,_green,_Golden_Super_Cruise_2026-09-05.jpg"),
        "dodge_challenger_hellcat" to CarPhoto(R.drawable.car_dodge_challenger_hellcat, Color(0xFF6B7385), "Nikolai Bulykin", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:%D0%9A%D1%8B%D1%80%D0%B3%D0%B0%D1%83%D1%8B%D0%BB%D0%B4%D1%8B,_Dodge_Challenger_SRT_%D0%BD%D0%B0_%D0%91%D0%B8%D1%80%D0%BB%D0%B8%D0%BA.jpg"),
        "mercedes_amg_gt_r" to CarPhoto(R.drawable.car_mercedes_amg_gt_r, Color(0xFF87E667), "Alexander-93", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Mercedes-AMG_C190_GT_R_IMG_7306.jpg"),
        "toyota_supra_a80" to CarPhoto(R.drawable.car_toyota_supra_a80, Color(0xFF6B7385), "TTTNIS", "CC0", "https://commons.wikimedia.org/wiki/File:1996-2002_Toyota_Supra_rear.jpg"),
        "mazda_rx7_fd" to CarPhoto(R.drawable.car_mazda_rx7_fd, Color(0xFFE6233B), "Mr.choppers", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:1994_Mazda_RX-7_R2_in_Vintage_Red,_front_left_(Lime_Rock).jpg"),
        "audi_rs3" to CarPhoto(R.drawable.car_audi_rs3, Color(0xFFB8BFCC), "Alexander Migl", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Audi_A3_8Y_Sedan_IMG_5936.jpg"),
        "f1_v10_2004" to CarPhoto(R.drawable.car_f1_v10_2004, Color(0xFFE62722), "Rick Dikeman", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:Michael_Schumacher_Ferrari_2004.jpg"),
        "bugatti_chiron" to CarPhoto(R.drawable.car_bugatti_chiron, Color(0xFF2895E6), "Calreyn88", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Bugatti_Chiron_1.jpg"),
        "lexus_lfa" to CarPhoto(R.drawable.car_lexus_lfa, Color(0xFFB8BFCC), "Tennen-Gas", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:Lexus_LFA_001.JPG"),
        "porsche_carrera_gt" to CarPhoto(R.drawable.car_porsche_carrera_gt, Color(0xFFB8BFCC), "Brian Snelson from Hockley, Essex, England", "CC BY 2.0", "https://commons.wikimedia.org/wiki/File:Porsche_Carrera_GT_-_Goodwood_Breakfast_Club_(July_2008).jpg"),
        "bmw_m5_e60" to CarPhoto(R.drawable.car_bmw_m5_e60, Color(0xFF406BE6), "Ethan Llamas", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:BMW_M5_E60_Interlagos_Blue_Metallic_01.jpg"),
        "ferrari_812_superfast" to CarPhoto(R.drawable.car_ferrari_812_superfast, Color(0xFFE65260), "Alexander-93", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Ferrari_812_Superfast_IMG_8829.jpg"),
        "aston_martin_valkyrie" to CarPhoto(R.drawable.car_aston_martin_valkyrie, Color(0xFF224DE6), "Matti Blume", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Aston_Martin_Valkyrie,_BAS_24,_Brussels_(P1170224-RR).jpg"),
        "mclaren_720s" to CarPhoto(R.drawable.car_mclaren_720s, Color(0xFFE66C22), "Vauxford", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2018_McLaren_720S_V8_S-A_4.0.jpg"),
        "corvette_z06_c8" to CarPhoto(R.drawable.car_corvette_z06_c8, Color(0xFFB8BFCC), "UltraTech66", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2023_Chevrolet_Corvette_Z06.jpg"),
        "nissan_patrol_y62" to CarPhoto(R.drawable.car_nissan_patrol_y62, Color(0xFFB8BFCC), "Dinkun Chen", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:NISSAN_PATROL_Y62_China_(16).jpg"),
        "toyota_land_cruiser_v8" to CarPhoto(R.drawable.car_toyota_land_cruiser_v8, Color(0xFFB8BFCC), "Dinkun Chen", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:TOYOTA_LAND_CRUISER_200_China_(3).jpg"),
        "subaru_wrx_sti" to CarPhoto(R.drawable.car_subaru_wrx_sti, Color(0xFF5479E6), "Rockmanoliverr", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:Subaru_WRX_STI_GJ.jpg"),
        "honda_s2000" to CarPhoto(R.drawable.car_honda_s2000, Color(0xFFB8BFCC), "Rich Niewiroski Jr.", "CC BY 2.5", "https://commons.wikimedia.org/wiki/File:HondaS2000-004.jpg"),
        "nissan_skyline_r34" to CarPhoto(R.drawable.car_nissan_skyline_r34, Color(0xFF4786E6), "Calreyn88", "CC BY-SA 4.0", "https://commons.wikimedia.org/wiki/File:2001_Nissan_Skyline_GT-R_V-Spec_II_R34_(79241).jpg"),
        "bmw_m3_e46" to CarPhoto(R.drawable.car_bmw_m3_e46, Color(0xFFE6D052), "Beemwej", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:BMW_M3_coupe_e46_PL.JPG"),
    )

    fun of(id: String): CarPhoto? = all[id]
}
