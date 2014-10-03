jQuery(document).ready(function ($) {



    // @Patrick  the fixed top nav code  start from here

    //top nav
    var my_nav = $('.navbar-sticky');
    // grab the initial top offset of the navigation 
    var sticky_navigation_offset_top = my_nav.offset().top;

    // function to decide weather the navigation bar should have fixed css position or not.
    var sticky_navigation = function () {
        //  current vertical position from the top
        var scroll_top = $(window).scrollTop();


        if (scroll_top > sticky_navigation_offset_top) {
            my_nav.addClass('stick');
        } else {
            my_nav.removeClass('stick');
        }
    };


    //parallax effect
    var parallax_animation = function () {
        $('.parallax').each(function (i, obj) {
            var speed = $(this).attr('parallax-speed');
            if (speed) {
                var background_pos = '-' + (window.pageYOffset / speed) + "px";
                $(this).css('background-position', 'center ' + background_pos);
            }
        });
    }


   // page elements animation 
    var image_animation = function () {
        var diagramTop = $('#diagram').offset().top;
		var coremTop = $('#core').offset().top;

        
        var scroll_top = $(window).scrollTop();
        var currentPosition = scroll_top + 320;

        if (diagramTop < currentPosition) {
            $('#diagram').addClass("animated fadeIn");
        } else {
            $('#diagram').removeClass("animated fadeIn");
        }

        if (coremTop< currentPosition) {

            $('#core').addClass("animated fadeInRight");
        } else {
            $('#core').removeClass("animated fadeInRight");
        }







    }
 




    $(document).scroll(function () {
        sticky_navigation();
        parallax_animation();
		image_animation();
       

    });



});