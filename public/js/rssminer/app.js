(function () {
  var _RM_ = window._RM_ || {},
      RM = window.RM,
      router = RM.Router,
      data_api = RM.data,
      notify = RM.notify,
      tmpls = RM.tmpls,
      to_html = function () {
        var args = _.toArray(arguments);
        if(args.length === 2) {
          args.push(tmpls);
        }
        return Mustache.render.apply({}, args);
      },
      util = RM.util,
      layout = RM.layout,
      location = window.location,
      call_if_fn = util.call_if_fn;

  var SHOW_NAV = 'show-nav',
      MIN_TIME = 400,           // at least 400ms, then record
      SAVE_THREASH_HOLD = 3,
      DATA_ID = 'data-id',
      PRE_LOAD_ITEM = 5,
      TOP_DIFF = 300,
      SUMMARY_SELECTOR = '#s-',
      FEED_SELECTOR = '#feed-',
      BOTTOM_DIFF = 400,
      READING_CLS = 'reading',
      NEWEST_TAB = 'newest',
      READ_URL_PATTEN = 'read/:id/:id?p=:page&s=:sort',
      SHOW_CONTENT = 'show-content';

  var $footer = $('#footer'),
      $reading_area = $('#reading-area'),
      $navigation = $('#navigation'), // feed list
      $subs_list = $('#sub-list'),    // sub list
      $feed_content = $('#feed-content'),
      $dropdown = $('#dropdown'),
      $logo = $('#logo'),
      $welcome_list = $('#welcome-list');

  var gcur_page,
      gcur_sort,
      gcur_group,
      gcur_subid,
      greading_meta = {},
      greading_times = {},
      GROUP_FOLDER = 'GROUP',
      GROUP_WELCOME = 'ALL',
      GROUP_SUB = 'SUB';

  function _update_state (subid, page, sort, group) {
    $welcome_list.scrollTop(0);
    $feed_content.empty(); // save memory, when reading list
    $reading_area.scrollTop(0);
    gcur_page = page;
    gcur_sort = sort;
    gcur_group = group;
    gcur_subid = subid;
  }

  function set_document_title (title) {
    var rssminer = 'Rssminer, intelligent RSS reader';
    if(title) {
      if(title.toLowerCase().indexOf('rssminer') !== -1) {
        document.title = title;
      } else {
        document.title = title + ' - ' + rssminer;
      }
    } else {
      document.title = rssminer;
    }
  }

  function read_subscription (id, page, sort, callback) {
    _update_state(id, page, sort, GROUP_SUB);
    $reading_area.removeClass(SHOW_CONTENT);
    layout.select('#sub-list', $("#item-" + id));
    data_api.fetch_sub_feeds(id, page, sort, function (data) {
      show_feeds(data);
      call_if_fn(callback);
    });
  }

  function read_group_subs (group, page, sort, callback) {
    _update_state(group, page, sort, GROUP_FOLDER);
    layout.select('#sub-list', $("[data-name='" + group + "']"));
    data_api.fetch_group_feeds(group, page, sort, function (data) {
      show_feeds(data);
      call_if_fn(callback);
    });
  }

  function decrement_number ($just_read, subid) {
    var selector = "#item-" + subid;
    if($just_read.hasClass('neutral')) {
      selector += ' .unread-neutral';
    } else if ($just_read.hasClass('like')) {
      selector += ' .unread-like';
    } else { selector += ' .unread-dislike'; }
    var $n = $(selector),
        n = parseInt($.trim($n.text()) || "0");
    if(n === 1) {
      $n.parents('.has-like').removeClass('has-like');
      $n.remove();
    }
    else { $n.text(n-1); }
  }

  function record_reading_time (url_pattern, args) {
    if(greading_meta.read) {
      var time = new Date() - greading_meta.start;
      if(time > MIN_TIME) {
        time = parseInt(Math.floor(time/100)); // save store it in 0.1s
        greading_times[greading_meta.id] = time;
        if(_.keys(greading_times).length >= SAVE_THREASH_HOLD) {
          var data = greading_times;
          greading_times = {};  // empty it
          data_api.save_reading_times(data);
        }
      }
    }
    if(url_pattern !== READ_URL_PATTEN) {
      greading_meta.read = false;
    }
  }

  function select_and_compute_fetch_ids (feedid, scroll_up) {
    var $me = $(FEED_SELECTOR + feedid);
    $reading_area.addClass(SHOW_CONTENT);
    $welcome_list.empty();
    $logo.removeClass(SHOW_NAV);
    layout.select('#feed-list', $me);
    $navigation.scroll(); // trigger scroll if has need load more

    var ids=  [feedid];
    var next = PRE_LOAD_ITEM - 1, prev = PRE_LOAD_ITEM -1;
    if(scroll_up === true) {
      prev = PRE_LOAD_ITEM;
      next = 1;
    } else if(scroll_up === false) {
      prev = 1;
      next = PRE_LOAD_ITEM;
    }

    var $prev = $me.prev();
    while($prev.length && --prev >= 0) {
      ids.unshift($prev.attr(DATA_ID));
      $prev = $prev.prev();
    }

    // if(prev >= 1) { next += 1; }
    var $next = $me.next();
    while($next.length && --next >= 0) {
      ids.push($next.attr(DATA_ID));
      $next = $next.next();
    }
    return ids;
  }

  function cleanup ($me) {
    _.each(["p", 'pre'], function (selector) {
      $reading_area.find(selector).each(function (idx, p) {
        var $e = $(p);
        // only remove if no chillren and no text. 516264
        if(!$.trim($e.text()) && !$e.find('img').length) {
          $e.hide();            // 4037/330457
        }
      });
    });


    $reading_area.find('.summary a').each(function (idx, a) {
      $(a).attr('target', '_blank');
    });
  }

  function cleaning_and_scrollto (feedid, subid, scroll_up) {
    // console.log('reading', feedid);
    var $me = $(SUMMARY_SELECTOR + feedid);
    if(!$me.hasClass(READING_CLS)) {
      set_document_title($me.find('.feed h2').text());

      router.navigate($(FEED_SELECTOR + feedid).find('a').attr('href'));

      $me.find('img').css({display: 'block'});
      cleanup($me);

      $feed_content.find('.' + READING_CLS).removeClass(READING_CLS);
      $me.addClass(READING_CLS);

      var $feed = $(FEED_SELECTOR + feedid);
      if(!$feed.hasClass('read')) {
        decrement_number($feed, subid);
        $feed.removeClass('unread sys-read').addClass('read');
        data_api.mark_as_read(feedid);
      }
    }

    var count = 0;
    var $next = $me.next(), $prev = $me.prev();
    while($next.length) {
      if(count ++ > PRE_LOAD_ITEM - 1) {        // keep most PRE_LOAD_ITEM
        $next.remove();
      }
      $next = $next.next();
    }

    keep_position(feedid, function () {
      count = 0;
      while($prev.length) {
        if(count ++ > PRE_LOAD_ITEM - 1) {
          $prev.remove();
        }
        $prev = $prev.prev();
      }
    });

    if(scroll_up === undefined) {
      $me.prevAll().find('img').css({display: 'none'});
      $me.nextAll().find('img').css({display: 'block'});
      var p = $me.position();
      if(p) {                   // scroll based on current postion
        var s = $reading_area.scrollTop();
        disabled_scroll(p.top - s);
      }
    }
  }

  function keep_position (feedid, f) {
    var $me = $(SUMMARY_SELECTOR + feedid);
    if($me.length) {
      var top = $me.position().top;
      f();
      var newtop = $me.position().top;
      disabled_scroll(newtop - top);
    } else {
      f();
    }
  }

  function disabled_scroll (diff) {
    if(diff) {
      ignore_scroll_event = true;
      // console.log('scroll', diff);
      $reading_area[0].scrollTop += diff;
      _.delay(function () { ignore_scroll_event = false; }, 20);
    }
  }

  function read_callback (subid, feedid, page, sort, scroll_up) {
    return function () {
      gcur_subid = subid;
      gcur_sort = sort;
      gcur_page = page;

      var ids = select_and_compute_fetch_ids(feedid, scroll_up);
      ids = _.filter(ids, function (id) {
        return !$(SUMMARY_SELECTOR + id).length || id === feedid;
      });
      var idx = _.indexOf(ids, feedid);
      var insert_before = idx !== 0;
      ids = _.filter(ids, function (id) { // remove feedid if exits
        return !$(SUMMARY_SELECTOR + id).length;
      });
      // console.log(ids);
      var $f = $(FEED_SELECTOR + feedid); // only one
      if(ids.length > 1 ||(ids.length === 1 && !$f.next().next().length)) {
        data_api.fetch_summary(ids, function (feeds) {
          var $content = $(to_html(tmpls.feed_content, {feeds: feeds}));

          var duplicates = [];
          $content.filter('li').each(function (idx, li) {
            if($('#' + li.id).length) { duplicates.push(li); }
          });
          _.each(duplicates, function (li) {
            // console.log('remove', li);
            li.parentNode.removeChild(li);
          });

          if(insert_before === true) {
            keep_position(feedid, function () {
              $feed_content.prepend($content);
            });
          } else {
            $feed_content.append($content);
          }
          cleaning_and_scrollto(feedid, subid, scroll_up);
        });
      } else {
        cleaning_and_scrollto(feedid, subid, scroll_up);
      }
    };
  }

  function read_feed (subid, feedid, page, sort, folder, scroll_up) {
    // console.log('read', feedid);
    var read_cb = read_callback(subid, feedid, page, sort, scroll_up);
    if(gcur_subid === subid) {
      read_cb();                   // just read feed
    } else {
      if(_.isNumber(subid)) {
        read_subscription(subid, page, sort, read_cb);
      } else if(folder) {
        read_group_subs(subid, page, sort, read_cb);
      } else {
        show_welcome(subid, page, read_cb);
      }
    }
  }

  function read_group_feed (group, feedid, page, sort) {
    read_feed(group, feedid, page, sort, group);
  }

  function mark_feed_as_read ($me, feedid, subid) {
    if(!$me.hasClass('read')) {
      data_api.mark_as_read(feedid);
      decrement_number($me, subid);
      $me.removeClass('unread sys-read').addClass('read');
    }
  }

  function show_feeds (data) {
    util.add_even(data.feeds);
    gcur_has_more = data.pager.has_more;
    show_server_message();
    // iframe.src = 'about:blank';
    var html = to_html(tmpls.feeds_nav, data);
    $navigation.empty().append(html);
    html = to_html(tmpls.sub_feeds, data);
    $welcome_list.empty().append(html);
    set_document_title(data.title);
    $reading_area.removeClass(SHOW_CONTENT);
    $logo.addClass(SHOW_NAV);
  }

  function show_welcome (section, page, cb) {
    var d = !section && !page;
    section = section || data_api.user_conf.pref_sort || NEWEST_TAB;
    page = page || 1;
    _update_state(section, page, section, GROUP_WELCOME);
    $subs_list.find('.selected').removeClass('selected');
    if(data_api.get_subscriptions().length) { // user has subscriptions
      data_api.fetch_welcome(section, page, section, function (data) {
        if(!data.feeds.length && d) {
          // try to show something that has data
          location.hash = '?s=newest&p=1';
        } else {
          show_feeds(data, section);
          call_if_fn(cb);
        }
      });
    } else {
      location.hash = "s/add";
      show_server_message();
    }
  }

  function show_settings (section) {
    $reading_area.removeClass(SHOW_CONTENT);
    var old_sort = data_api.user_conf.pref_sort;
    var sections = ['add', 'settings', 'help'],
        sortings = [{value: NEWEST_TAB,
                     s: old_sort === NEWEST_TAB},
                    {value: 'recommend', // default to recommend
                     s: old_sort === 'recommend'}];
    var d = {
      selected: section,
      sortings: sortings,
      tabs: _.map(sections, function (s) {
        return { n: s, s: s === section };
      })
    };
    d.demo = _RM_.demo;
    var html = to_html(tmpls.settings, d);
    $welcome_list.empty().append(html);
  }

  function save_settings (e) {
    var d = util.extract_data( $('#all-settings .account') );
    for(var i in d) { if(!d[i]) { delete d[i]; } }
    if(d.password && d.password !== d.password2) {
      alert('password not match');
      return;
    }
    delete d.password2;
    data_api.save_settings(d, function () {
      notify.show_msg('Settings saved', 3000);
    });
  }

  function still_in_settings () {
    return location.hash === "#s/add";
  }

  function fetcher_finished (result) {
    if(!result) {
      notify.show_msg('Sorry, Fetcher is too busy.. job batched.', 10000);
      return ;
    }
    if(result.refresh) {
      fetch_and_show_user_subs(function () {
        // if user is waiting, just put he there
        if(still_in_settings()) {
          location.hash = 'read/' + result.id + '?p=1&s=newest';
        }
      });
    } else if(still_in_settings()){
      location.hash = 'read/' + result.id + '?p=1&s=newest';
    }
  }

  function import_from_greader (e) {
    if(_RM_.demo) {
      alert('If you like it, please login in with Google OpenID' +
            ', then import. This is a public account');
      return false;
    }
  }

  function add_subscription (e) {
    var $input = $("#rss_atom_url"),
        url = $.trim($input.val()),
        added = function () {
          $input.val('');
          notify.show_msg('Subscription added successfully', 400);
          window.setTimeout(function () {
            // if user is waiting, just put he there
            if(still_in_settings()) {
              notify.show_msg('Working hard to fetch the feeds...', 10000);
            }
          }, 1500);
        };

    if(url && url.indexOf('http://') === 0) {
      if(_RM_.demo) {
        alert('This is a demo account');
        return;
      }
      data_api.add_subscription(url, added, fetcher_finished);
    } else {
      notify.show_msg('Not a valid rss/atom link', 3000);
    }
  }

  function fetch_and_show_user_subs (cb) {
    data_api.get_user_subs(function (subs) {
      var html = to_html(tmpls.subs_nav, {groups: subs});
      $subs_list.empty().append(html).find('img').each(util.favicon_ok);
      $subs_list.trigger('refresh.rm');
      util.call_if_fn(cb);
    });
  }

  var is_loading = false;
  var gcur_has_more = true;
  function on_navigation_scroll(e) {     // feed list scroll, auto load
    if(!gcur_has_more) { $('#navigation .loader').remove(); return; }
    if(is_loading) { return; }
    var total_height = $navigation[0].scrollHeight, // ie8, ff, chrome
        scrollTop = $navigation.scrollTop(),
        height = $navigation.height();
    if(scrollTop + height === total_height) {
      var fn = data_api.fetch_sub_feeds;
      if( gcur_group === GROUP_FOLDER) {
        fn = data_api.fetch_group_feeds;
      } else if (gcur_group === GROUP_WELCOME ) {
        fn = data_api.fetch_welcome;
      }
      gcur_page += 1;
      is_loading = true;
      fn(gcur_subid, gcur_page, gcur_sort, function (data) {
        is_loading = false;
        gcur_has_more = data.pager && data.pager.has_more;
        if(!gcur_has_more) {
          $('#navigation .loader').remove();
        } else {
          var html = to_html(tmpls.feeds_list, data);
          $('#feed-list').append(html);
        }
      });
    }
  };

  function is_reading () {
    return $reading_area.hasClass(SHOW_CONTENT);
  }

  var previous_scroll = 0;
  var ignore_scroll_event = false; // app call scroll, ignore event
  function on_readarea_scroll(e) {
    if(ignore_scroll_event || !is_reading()) { return; }
    var current_scroll = $reading_area.scrollTop(),
        $reading = $reading_area.find('.reading'),
        // TODO, not just prev, next, since HOME/END key
        $prev = $reading.prev(),
        $next = $reading.next();
    if (current_scroll > previous_scroll && $next.length) { // down
      if($next.position().top - current_scroll < TOP_DIFF) {
        var id = parseInt($next.find('.feed').attr(DATA_ID));
        read_feed(gcur_subid, id, gcur_page, gcur_sort, gcur_group, false);
      }
    } else if($prev.length) {                  // up
      var height = $reading_area.height();
      if($reading.position().top - current_scroll - height > -BOTTOM_DIFF) {
        id = parseInt($prev.find('.feed').attr(DATA_ID));
        read_feed(gcur_subid, id, gcur_page, gcur_sort, gcur_group, true);
      }
    }
    previous_scroll = current_scroll;
  };

  function show_server_message () { // only show once
    if(_RM_.gw) {               // google import wait
      var msg = 'Busy importing from google reader, please refresh in a few seconds';
      notify.show_msg(msg, 10000);
      _RM_.gw = 0;
    }
    if(_RM_.ge) {
      var msg2 = 'Error: ' + _RM_.ge + ' , please try again';
      notify.show_msg(msg2, 6000);
      _RM_.ge = 0;
    }
  }

  function save_pref_sort (e) {
    var val = $(this).val();
    data_api.save_settings({pref_sort: val}, function () {
      fetch_and_show_user_subs();
      notify.show_msg('Settings saved', 3000);
    });
  }

  function close_shortcut_help () {
    $('#shortcuts').fadeOut();
    $('#overlay').hide();
  }

  function show_shortcut_help () {
    $('#shortcuts').fadeIn();
    $('#overlay').show();
    return false;
  }

  function search (q, tags, authors, offset) {
    var fs = !$('#search-result').length || offset === 0;
    data_api.fetch_search(q, tags, authors, offset, fs, function (data) {
      data.q = q;
      var $html = $(to_html(tmpls.search_result, data));
      $reading_area.removeClass(SHOW_CONTENT);
      if(fs) {
        $welcome_list.empty().append($html);
      } else {
        $('#search-result .feeds').replaceWith($('.feeds', $html));
        $('#search-result .pager').replaceWith($('.pager', $html));
      }
      // $('#search-go input').focus();
    });
  }

  function update_search_hash (e) {
    var val = $.trim($(this).val());
    var $link = $('#search-go').find('a');
    // var hash = 'search?q=&tags=&authors=&offset=0';
    var hash = location.hash;
    hash = hash.replace(/q=.*?&/, function (a, b, c) {
      return "q=" + val + '&';
    });
    $link.attr('href', hash);
    if(e.which === 13) {
      location.hash = hash;
    }
  }

  util.delegate_events($(document), {
    'click .add-sub a.import': import_from_greader,
    'change #all-settings select': save_pref_sort,
    'keyup #search-go input': update_search_hash,
    'click #add-subscription': add_subscription,
    'click .show-shortcuts': show_shortcut_help,
    'click #save-settings': save_settings,
    'click #overlay': close_shortcut_help,
    'click .icon-ok-circle': close_shortcut_help,
    'click #dropdown li': function () { $dropdown.addClass('clicked'); },
    'mouseenter #logo': function () { $logo.addClass(SHOW_NAV); },
    'mouseleave #logo': function () {
      if(/#read\/.+\/\d+/.test(location.hash)) { // if reading feed
        _.delay(function () { $logo.removeClass(SHOW_NAV); }, 50);
      }
    }
  });

  fetch_and_show_user_subs(function () { // app start here
    router.route({
      '': show_welcome,
      '?s=:section&p=:p': show_welcome,
      's/:section': show_settings,
      'read/f_:group?p=:page&s=:sort': read_group_subs,
      'read/:id?p=:page&s=:sort': read_subscription,
      'read/f_:group/:id?p=:page&s=:sort': read_group_feed,
      'read/:id/:id?p=:page&s=:sort': read_feed,
      'search?q=:q&tags=:tags&authors=:authors&offset=:offset': search
    });
  });


  $reading_area.scroll(on_readarea_scroll);
  $navigation.scroll(on_navigation_scroll);

  if(_RM_.demo) { $('#warn-msg').show(); }
  $dropdown.hover(function () { $dropdown.removeClass('clicked'); });
})();
