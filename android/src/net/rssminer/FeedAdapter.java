package net.rssminer;

import java.util.List;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

public class FeedAdapter extends ArrayAdapter<Feed> {

	private final LayoutInflater inflater;
	private final int layoutId;
	public final List<Feed> feeds;

	public FeedAdapter(Context context, int textViewResourceId,
			List<Feed> objects) {
		super(context, textViewResourceId, objects);
		layoutId = textViewResourceId;
		this.feeds = objects;
		inflater = (LayoutInflater) getContext().getSystemService(
				Context.LAYOUT_INFLATER_SERVICE);
	}

	public View getView(int position, View convertView, ViewGroup parent) {

		if (convertView == null) {
			convertView = inflater.inflate(layoutId, null);
		}
		Feed feed = this.getItem(position);
		TextView title = (TextView) convertView.findViewById(R.id.feed_title);
		title.setText(feed.title);

		TextView time = (TextView) convertView.findViewById(R.id.feed_time);
		time.setText(Utils.dateStr(feed.publishTs));

		TextView author = (TextView) convertView.findViewById(R.id.feed_author);
		author.setText(feed.author);

		if (feed.readts > 0) {
			Log.d("AAA", feed.title);
			title.setTextColor(Constants.DIM_COLOR);
		}

		return convertView;
	}
}
