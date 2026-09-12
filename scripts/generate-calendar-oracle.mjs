// Run with a sibling personal-life-os checkout; fixtures never read private data.
import { expandEventSeries } from '../../personal-life-os/lib/calendar-events.ts';
import { writeFileSync } from 'node:fs';
const base = { id: 'fixture', title: 'Собрание', notes: '', location: '', tags: [], allDay: false, startDate: '2026-01-01', startTime: '10:00', durationMinutes: 60, repeat: {frequency: 'none', interval: 1}, exceptions: {} };
const definitions = [
 ['weekly-count', '2026-01-01', '2026-04-01', {repeat: {frequency:'weekly', interval:2, weekdays:[0,1,4], count:9}}],
 ['monthly-31', '2026-01-01', '2027-01-01', {startDate:'2026-01-31', repeat:{frequency:'monthly', interval:1, count:6}}],
 ['last-weekday', '2026-01-01', '2027-01-01', {startDate:'2026-01-30', repeat:{frequency:'monthly',interval:1,monthlyMode:'weekday'}}],
 ['second-weekday', '2026-01-01', '2027-01-01', {startDate:'2026-01-12', repeat:{frequency:'monthly',interval:2,monthlyMode:'weekday'}}],
 ['leap-birthday', '2028-01-01', '2029-01-01', {startDate:'2024-02-29', allDay:true,startTime:'00:00',durationMinutes:1440, repeat:{frequency:'yearly', interval:1, count:2}}],
 ['until-crossing', '2026-02-01', '2026-03-01', {startDate:'2026-01-30', startTime:'23:45', durationMinutes:90, repeat:{frequency:'daily',interval:2,until:'2026-02-04'}}],
 ['exceptions', '2026-02-01', '2026-03-01', {repeat:{frequency:'weekly',interval:1},exceptions:{'2026-01-01':{startDate:'2026-02-02',title:'Перенесено'},'2026-02-05':{cancelled:true},'2026-02-12':{startDate:'2026-04-01'},'2026-02-19':{startTime:'21:00',durationMinutes:180},'2026-01-02':{startDate:'2026-02-07'}}}],
 ['all-day-overlap', '2026-02-01', '2026-02-04', {startDate:'2026-01-31',allDay:true,startTime:'00:00',durationMinutes:2880}],
 ['boundary-exclusive', '2026-02-01', '2026-02-02', {startDate:'2026-01-31',startTime:'23:00',durationMinutes:60}],
];
const cases = definitions.map(([name, from, to, patch]) => {
 const series = [{...base,...patch}];
 return {name, from, to, series, expected: expandEventSeries(series,from,to).map(e=>({id:e.id,title:e.title,start:new Date(e.start).toISOString(),end:new Date(e.end).toISOString(),allDay:e.allDay,occurrenceDate:e.occurrenceDate}))};
});
writeFileSync(new URL('../app/src/test/resources/calendar-oracle.json', import.meta.url), JSON.stringify(cases,null,2)+'\n');
console.log(`Generated ${cases.length} recurrence cases from the service implementation`);
